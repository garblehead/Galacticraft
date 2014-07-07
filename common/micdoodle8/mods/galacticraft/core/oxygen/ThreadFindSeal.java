package micdoodle8.mods.galacticraft.core.oxygen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import micdoodle8.mods.galacticraft.api.block.IPartialSealableBlock;
import micdoodle8.mods.galacticraft.api.vector.BlockVec3;
import micdoodle8.mods.galacticraft.core.GCCoreConfigManager;
import micdoodle8.mods.galacticraft.core.blocks.GCCoreBlockUnlitTorch;
import micdoodle8.mods.galacticraft.core.blocks.GCCoreBlocks;
import micdoodle8.mods.galacticraft.core.tick.GCCoreTickHandlerServer;
import micdoodle8.mods.galacticraft.core.tile.GCCoreTileEntityOxygenSealer;
import micdoodle8.mods.galacticraft.core.wrappers.ScheduledBlockChange;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEnchantmentTable;
import net.minecraft.block.BlockFarmland;
import net.minecraft.block.BlockFluid;
import net.minecraft.block.BlockGlass;
import net.minecraft.block.BlockGravel;
import net.minecraft.block.BlockHalfSlab;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockSponge;
import net.minecraft.block.material.Material;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import cpw.mods.fml.common.FMLLog;

/**
 * ThreadFindSeal.java
 * 
 * This file is part of the Galacticraft project
 * 
 * @author micdoodle8, radfast
 * @license Lesser GNU Public License v3 (http://www.gnu.org/licenses/lgpl.html)
 * 
 */
public class ThreadFindSeal
{
	public AtomicBoolean sealedFinal = new AtomicBoolean();
	public static AtomicBoolean anylooping = new AtomicBoolean();
	public AtomicBoolean looping = new AtomicBoolean();
	
	private World world;
	private BlockVec3 head;
	private boolean sealed;
	private List<GCCoreTileEntityOxygenSealer> sealers;
	public HashSet<BlockVec3> checked;
	private int checkCount;
	private HashMap<BlockVec3, GCCoreTileEntityOxygenSealer> sealersAround;
	private List<BlockVec3> currentLayer;
	private List<BlockVec3> airToReplace;
	private List<BlockVec3> breatheableToReplace;
	private List<GCCoreTileEntityOxygenSealer> otherSealers;
	private List<BlockVec3> torchesToUpdate;

	public ThreadFindSeal(GCCoreTileEntityOxygenSealer sealer)
	{
		this(sealer.worldObj, new BlockVec3(sealer).translate(0, 1, 0), sealer.getFindSealChecks(), new ArrayList<GCCoreTileEntityOxygenSealer>(Arrays.asList(sealer)));
	}

	public ThreadFindSeal(World world, BlockVec3 head, int checkCount, List<GCCoreTileEntityOxygenSealer> sealers)
	{
		this.world = world;
		this.head = head;
		this.checkCount = checkCount;
		this.sealers = sealers;
		this.checked = new HashSet<BlockVec3>();
		this.torchesToUpdate = new LinkedList<BlockVec3>();
		
		this.sealersAround = new HashMap<BlockVec3, GCCoreTileEntityOxygenSealer>();
		for (TileEntity tile : new ArrayList<TileEntity>(world.loadedTileEntityList))
		{
			if (tile instanceof GCCoreTileEntityOxygenSealer && tile.getDistanceFrom(head.x, head.y, head.z) < 1024 * 1024)
			{
				this.sealersAround.put(new BlockVec3(tile.xCoord, tile.yCoord, tile.zCoord), (GCCoreTileEntityOxygenSealer) tile);
			}
		}

		//If called by a sealer test the head block and if partiallySealable mark its sides done as required
		if (!sealers.isEmpty())
		{
			if (checkCount>0)
			{	
				int id = head.getBlockID(this.world);
				if (id>0 && id!=GCCoreBlocks.breatheableAir.blockID)
				{
					this.canBlockPassAirCheck(id, this.head, 1);
					//reset the checkCount as canBlockPassAirCheck might have changed it
					this.checkCount = checkCount;
				}
			}

			this.looping.set(true);
			
			if (GCCoreConfigManager.enableSealerMultithreading)
				new ThreadedFindSeal();
			else
				this.check();
		} else
		//If not called by a sealer, it's a breathable air edge check
		{
			//Run this in the main thread
			this.check();
		}
	}

	//Multi-threaded version of the code for sealer updates (not for edge checks).
	public class ThreadedFindSeal extends Thread
	{
		public ThreadedFindSeal()
		{
			super("GC Sealer Roomfinder Thread");			
			ThreadFindSeal.anylooping.set(true);

			if (this.isAlive())
			{
				this.interrupt();
			}

			//Run this as a separate thread
			this.start();
		}
		
		@Override
		public void run()
		{
			check();
			ThreadFindSeal.anylooping.set(false);
		}
	}
	
	public void check()
	{	
		long time1 = System.nanoTime();

		this.sealed = true;
		this.checked.add(this.head.clone());
		this.currentLayer = new LinkedList<BlockVec3>();
		this.airToReplace = new LinkedList<BlockVec3>();

		if (this.checkCount > 0)
		{
			this.currentLayer.add(this.head);
			if (this.head.x < -29990000 || this.head.z < -29990000 || this.head.x >= 29990000 || this.head.z >= 29990000)
			{
				if (head.getBlockID(world)==0) this.airToReplace.add(this.head.clone());
				this.doLayerNearMapEdge();
			}
			else
			{
				if (head.getBlockIDsafe(world)==0) this.airToReplace.add(this.head.clone());
				this.doLayer();
			}
		}
		else
		{
			this.sealed = false;
		}

		long time2 = System.nanoTime();

		//Can only be properly sealed if there is at least one sealer here (on edge check)
		if (this.sealers.isEmpty()) this.sealed=false;
		
		if (this.sealed)
		{
			if (!this.airToReplace.isEmpty())
			{
				List<ScheduledBlockChange> changeList = new LinkedList<ScheduledBlockChange>();
				// Note: it is faster to use a LinkedList than an ArrayList
				// when adding a lot of small elements to a list
				
				int breatheableAirID = GCCoreBlocks.breatheableAir.blockID;
				for (BlockVec3 checkedVec : this.airToReplace)
				{
					//No block update for performance reasons; deal with unlit torches separately
					changeList.add(new ScheduledBlockChange(checkedVec.clone(), breatheableAirID, 0));
				}
				GCCoreTickHandlerServer.scheduleNewBlockChange(this.world.provider.dimensionId, changeList);

				if (!this.torchesToUpdate.isEmpty())
				{
					GCCoreTickHandlerServer.scheduleNewTorchUpdate(this.world.provider.dimensionId, this.torchesToUpdate);
				}
			}
		}
		else
		{
			HashSet checkedSave = this.checked;
			this.checked = new HashSet<BlockVec3>();
			this.breatheableToReplace = new LinkedList<BlockVec3>();
			this.otherSealers = new LinkedList<GCCoreTileEntityOxygenSealer>();
			// loopThroughD will mark breatheableAir blocks for change as it
			// finds them, also searches for unchecked sealers
			this.currentLayer.clear();
			this.currentLayer.add(this.head);
			this.torchesToUpdate.clear();
			if (this.head.x < -29990000 || this.head.z < -29990000 || this.head.x >= 29990000 || this.head.z >= 29990000)
			{
				this.loopThroughDNearMapEdge();
			}
			else
			{
				this.loopThroughD();
			}

			if (!this.otherSealers.isEmpty())
			{
				// OtherSealers will have members if the space to be made
				// unbreathable actually still has an unchecked sealer in it
				List<GCCoreTileEntityOxygenSealer> sealersSave = this.sealers;
				List<BlockVec3> torchesSave = this.torchesToUpdate;
				List<GCCoreTileEntityOxygenSealer> sealersDone = new ArrayList();
				sealersDone.addAll(this.sealers);
				for (GCCoreTileEntityOxygenSealer otherSealer : this.otherSealers)
				{
					// If it hasn't already been counted, need to check the
					// other sealer immediately in case it can keep the space
					// sealed
					if (!sealersDone.contains(otherSealer) && otherSealer.getFindSealChecks() > 0)
					{
						BlockVec3 newhead = new BlockVec3(otherSealer).translate(0, 1, 0);
						this.sealed = true;
						this.checkCount = otherSealer.getFindSealChecks();
						this.sealers = new LinkedList<GCCoreTileEntityOxygenSealer>();
						this.sealers.add(otherSealer);
						this.checked = new HashSet<BlockVec3>();
						this.checked.add(newhead);
						this.currentLayer.clear();
						this.airToReplace.clear();
						this.torchesToUpdate = new LinkedList<BlockVec3>();
						this.currentLayer.add(newhead.clone());
						if (newhead.x < -29990000 || newhead.z < -29990000 || newhead.x >= 29990000 || newhead.z >= 29990000)
						{
							this.doLayerNearMapEdge();
						}
						else
						{
							this.doLayer();
						}

						// If found a sealer which can still seal the space, it
						// should take over as head
						if (this.sealed)
						{
							if (GCCoreConfigManager.enableDebug)
							{
								FMLLog.info("Oxygen Sealer replacing head at x" + this.head.x + " y" + (this.head.y - 1) + " z" + this.head.z);
							}
							if (!sealersSave.isEmpty())
							{
								GCCoreTileEntityOxygenSealer oldHead = sealersSave.get(0);
								if (!this.sealers.contains(oldHead))
								{
									this.sealers.add(oldHead);
								}
							}
							this.head = newhead.clone();
							otherSealer.threadSeal = this;
							otherSealer.stopSealThreadCooldown = 75+GCCoreTileEntityOxygenSealer.countEntities;
							checkedSave.addAll(this.checked);
							break;
						} else
						{
							sealersDone.addAll(this.sealers);
						}

						checkedSave.addAll(this.checked);
					}
				}
				
				// Restore sealers to what it was, if this search did not
				// result in a seal
				if (!this.sealed)
				{
					this.sealers = sealersSave;
					this.torchesToUpdate = torchesSave;
				}
				else
				{
					//If the second search sealed the area, there may also be air or torches to update
					if (!this.airToReplace.isEmpty())
					{
						List<ScheduledBlockChange> changeList = new LinkedList<ScheduledBlockChange>();
						
						int breatheableAirID = GCCoreBlocks.breatheableAir.blockID;
						for (BlockVec3 airVec : this.airToReplace)
						{
							changeList.add(new ScheduledBlockChange(airVec.clone(), breatheableAirID, 0));
						}
						GCCoreTickHandlerServer.scheduleNewBlockChange(this.world.provider.dimensionId, changeList);

						if (!this.torchesToUpdate.isEmpty())
						{
							GCCoreTickHandlerServer.scheduleNewTorchUpdate(this.world.provider.dimensionId, this.torchesToUpdate);
						}
					}
				}
			}
			this.checked = checkedSave;

			if (!this.sealed)
			{
				if (head.getBlockID(world)==GCCoreBlocks.breatheableAir.blockID) this.breatheableToReplace.add(head);
				if(!this.breatheableToReplace.isEmpty())
				{
					List<ScheduledBlockChange> changeList = new LinkedList<ScheduledBlockChange>();
					for (BlockVec3 checkedVec : this.breatheableToReplace)
					{
						changeList.add(new ScheduledBlockChange(checkedVec.clone(), 0, 0));
					}
					GCCoreTickHandlerServer.scheduleNewBlockChange(this.world.provider.dimensionId, changeList);

					if (!this.torchesToUpdate.isEmpty())
					{
						GCCoreTickHandlerServer.scheduleNewTorchUpdate(this.world.provider.dimensionId, this.torchesToUpdate);
					}
				}
			}
		}

		// Set any sealers found which are not the head sealer, not to run their
		// own seal checks for a while
		// (The player can control which is the head sealer in a space by
		// enabling just that one and disabling all the others)
		GCCoreTileEntityOxygenSealer headSealer = this.sealersAround.get(this.head.clone().translate(0, -1, 0));

		// If it is sealed, cooldown can be extended as frequent checks are not needed
		if (headSealer != null) headSealer.stopSealThreadCooldown += 75;
		
		for (GCCoreTileEntityOxygenSealer sealer : this.sealers)
		{
			// Sealers which are not the head sealer: put them on cooldown so
			// the inactive ones don't start their own threads and so unseal
			// this volume
			// and update threadSeal reference of all sealers found (even the
			// inactive ones)
			if (sealer != headSealer && headSealer != null)
			{
				sealer.threadSeal = this;
				sealer.stopSealThreadCooldown = headSealer.stopSealThreadCooldown + 51;
			}
		}

		this.looping.set(false);

		if (GCCoreConfigManager.enableDebug)
		{
			long time3 = System.nanoTime();
			FMLLog.info("Oxygen Sealer Check Completed at x" + this.head.x + " y" + this.head.y + " z" + this.head.z);
			FMLLog.info("   Sealed: " + this.sealed);
			FMLLog.info("   Loop Time taken: " + (time2 - time1) / 1000000.0D + "ms");
			FMLLog.info("   Place Time taken: " + (time3 - time2) / 1000000.0D + "ms");
			FMLLog.info("   Total Time taken: " + (time3 - time1) / 1000000.0D + "ms");
			FMLLog.info("   Found: " + this.sealers.size() + " sealers");
			FMLLog.info("   Looped through: " + this.checked.size() + " blocks");
		}

		this.sealedFinal.set(this.sealed);
	}

	private void loopThroughD()
	{
		//Local variables are fractionally faster than statics 
		int breatheableAirID = GCCoreBlocks.breatheableAir.blockID;
		int oxygenSealerID = GCCoreBlocks.oxygenSealer.blockID;
		HashSet<BlockVec3> checkedLocal = this.checked;
		LinkedList nextLayer = new LinkedList<BlockVec3>();

		while (this.currentLayer.size() > 0)
		{
			for (BlockVec3 vec : this.currentLayer)
			{
				int side = 0;
				do
				{
					if (!vec.sideDone[side])
					{
						BlockVec3 sideVec = vec.newVecSide(side);

						if (!checkedLocal.contains(sideVec))
						{
							int id = sideVec.getBlockIDsafe(this.world);

							if (id == breatheableAirID)
							{
								this.breatheableToReplace.add(sideVec);
								// Only follow paths with adjacent breatheableAir
								// blocks - this now can't jump through walls etc
								nextLayer.add(sideVec);
								checkedLocal.add(sideVec);
							}
							else if (id == oxygenSealerID)
							{
								GCCoreTileEntityOxygenSealer sealer = this.sealersAround.get(sideVec);
								
								if (sealer != null && !this.sealers.contains(sealer))
								{
									if (side == 0)
									{
										//Accessing the vent side of the sealer, so add it
										this.otherSealers.add(sealer);
										checkedLocal.add(sideVec);
									}
									//if side is not 0, do not add to checked so can be rechecked from other sides
								} else
									checkedLocal.add(sideVec);
							}
							else
							{
								checkedLocal.add(sideVec);
								if (id>0 && this.canBlockPassAirCheck(id, sideVec, side))
								{
									//Look outbound through partially sealable blocks in case there is breatheableAir to clear beyond
									nextLayer.add(sideVec);
								}
							}
						}
					}
					side++;
				} while (side < 6);
			}

			// Set up the next layer as current layer for the while loop
			this.currentLayer = nextLayer;
			nextLayer = new LinkedList<BlockVec3>();
		}
	}

	private void loopThroughDNearMapEdge()
	{
		//Local variables are fractionally faster than statics 
		int breatheableAirID = GCCoreBlocks.breatheableAir.blockID;
		int oxygenSealerID = GCCoreBlocks.oxygenSealer.blockID;
		HashSet<BlockVec3> checkedLocal = this.checked;
		LinkedList nextLayer = new LinkedList<BlockVec3>();
		
		while (this.currentLayer.size() > 0)
		{
			for (BlockVec3 vec : this.currentLayer)
			{
				for (int side = 0; side < 6; side++)
				{
					if (vec.sideDone[side]) continue;
					BlockVec3 sideVec = vec.newVecSide(side);

					if (!checkedLocal.contains(sideVec))
					{
						int id = sideVec.getBlockID(this.world);

						if (id == breatheableAirID)
						{
							this.breatheableToReplace.add(sideVec);
							// Only follow paths with adjacent breatheableAir
							// blocks - this now can't jump through walls etc
							nextLayer.add(sideVec);
							checkedLocal.add(sideVec);
						}
						else if (id == oxygenSealerID)
						{
							GCCoreTileEntityOxygenSealer sealer = this.sealersAround.get(sideVec);
						
							if (sealer != null && !this.sealers.contains(sealer))
							{
								if (side == 0)
								{
									//Accessing the vent side of the sealer, so add it
									this.otherSealers.add(sealer);
									checkedLocal.add(sideVec);
								}
								//if side is not 0, do not add to checked so can be rechecked from other sides
							} else
								checkedLocal.add(sideVec);
						}
						else
						{
							checkedLocal.add(sideVec);
							if (id>0 && this.canBlockPassAirCheck(id, sideVec, side))
							{
								//Look outbound through partially sealable blocks in case there is breatheableAir to clear beyond
								nextLayer.add(sideVec);
							}
						}
					}
				}
			}

			// Set up the next layer as current layer for the while loop
			this.currentLayer = nextLayer;
			nextLayer = new LinkedList<BlockVec3>();
		}
	}

	private void doLayer()
	{
		//Local variables are fractionally faster than statics 
		int breatheableAirID = GCCoreBlocks.breatheableAir.blockID;
		int oxygenSealerID = GCCoreBlocks.oxygenSealer.blockID;
		HashSet<BlockVec3> checkedLocal = this.checked;
		LinkedList nextLayer = new LinkedList<BlockVec3>();

		while (this.sealed && this.currentLayer.size() > 0)
		{
			int side;
			for (BlockVec3 vec : this.currentLayer)
			{
				//This is for side = 0 to 5 - but using do...while() is fractionally quicker
				side = 0;
				do
				{
					//Skip the side which this was entered from
					//This is also used to skip looking on the solid sides of partially sealable blocks
					if (!vec.sideDone[side])
					{
						// The sides 0 to 5 correspond with the ForgeDirections
						// but saves a bit of time not to call ForgeDirection
						BlockVec3 sideVec = vec.newVecSide(side);

						if (!checkedLocal.contains(sideVec))
						{
							if (this.checkCount > 0)
							{
								this.checkCount--;
								checkedLocal.add(sideVec);

								int id = sideVec.getBlockIDsafe(this.world);
								if (id == breatheableAirID)
									// The most likely case
								{
									nextLayer.add(sideVec);
								}	
								else if (id <= 0)
								{
									if (id == -1)
									{
										// Broken through to the void or the
										// stratosphere (above y==255) - set
										// unsealed and abort
										this.checkCount = 0;
										this.sealed = false;
										return;
									}
									if (id == 0)
									{
										//id==0 is air
										nextLayer.add(sideVec);
										this.airToReplace.add(sideVec);								
									}
									// The other possibility is id==-2
									// Meaning it has encountered an unloaded chunk
									// Simply treat it as solid: do nothing.
								}
								else if (this.canBlockPassAirCheck(id, sideVec, side))
								{
									nextLayer.add(sideVec);
								}
								else if (id == oxygenSealerID)
								{
									GCCoreTileEntityOxygenSealer sealer = this.sealersAround.get(sideVec);

									if (sealer != null && !this.sealers.contains(sealer))
									{
										if (side == 0)
										{
											this.sealers.add(sealer);
											this.checkCount += sealer.getFindSealChecks();
										} else
											//Allow this sealer to be checked from other sides
											checkedLocal.remove(sideVec);
									}
								}
							}
							// the if (this.isSealed) check here is unnecessary because of the returns
							else
							{
								int id = sideVec.getBlockIDsafe(this.world);
								if (id <= 0)
								{
									//id==0 is air, id==-1 means the void or height y>255
									//id==-2 means unloaded chunk: treat as solid
									if (id>=-1)
									{
										// Air or the void are unsealed obviously
										this.sealed = false;
										return;
									}
								} else
									//If there is breatheable air or an unsealed block here, that is also unsealed
									if (id == breatheableAirID || this.canBlockPassAirCheck(id, sideVec, side))
									{
										this.sealed = false;
										return;
									}
							}
						}
					}
					side++;
				} while (side < 6);
			}

			// Is there a further layer of air/permeable blocks to test?
			this.currentLayer = nextLayer;
			nextLayer = new LinkedList<BlockVec3>();
		}
	}

	private void doLayerNearMapEdge()
	{
		//Local variables are fractionally faster than statics 
		int breatheableAirID = GCCoreBlocks.breatheableAir.blockID;
		int oxygenSealerID = GCCoreBlocks.oxygenSealer.blockID;
		HashSet<BlockVec3> checkedLocal = this.checked;
		LinkedList nextLayer = new LinkedList<BlockVec3>();

		while (this.sealed && this.currentLayer.size() > 0)
		{
			for (BlockVec3 vec : this.currentLayer)
			{
				for (int side = 0; side < 6; side++)
				{
					// The sides 0 to 5 correspond with the ForgeDirections but
					// saves a bit of time not to call ForgeDirection
					if (vec.sideDone[side]) continue;
					BlockVec3 sideVec = vec.newVecSide(side);

					if (!checkedLocal.contains(sideVec))
					{
						if (this.checkCount > 0)
						{
							this.checkCount--;
							checkedLocal.add(sideVec);

							// This is a slower operation as it involves
							// map edge checks
							int id = sideVec.getBlockID(this.world); 

							if (id == breatheableAirID)
								// The most likely case
							{
								nextLayer.add(sideVec);
							}	
							else if (id <= 0)
							{
								if (id == -1)
								{
									// Broken through to the void or the
									// stratosphere (above y==255) - set
									// unsealed and abort
									this.checkCount = 0;
									this.sealed = false;
									return;
								}
								if (id == 0)
								{
									//id==0 is air
									nextLayer.add(sideVec);
									this.airToReplace.add(sideVec);								
								}
								// The other possibility is id==-2
								// Meaning it has encountered an unloaded chunk
								// Simply treat it as solid: do nothing.
							}
							else if (id == breatheableAirID || this.canBlockPassAirCheck(id, sideVec, side))
							{
								nextLayer.add(sideVec);
							}
							else if (id == oxygenSealerID)
							{
								GCCoreTileEntityOxygenSealer sealer = this.sealersAround.get(sideVec);

								if (sealer != null && !this.sealers.contains(sealer))
								{
									if (side == 0)
									{
										this.sealers.add(sealer);
										this.checkCount += sealer.getFindSealChecks();
									} else
										//Allow this sealer to be checked from other sides
										checkedLocal.remove(sideVec);
								}
							}
						}
						else if (this.sealed)
						{
							int id = sideVec.getBlockID(this.world);
							if (id <= 0)
							{
								//id==0 is air, id==-1 means the void or height y>255
								//id==-2 means unloaded chunk: treat as solid
								if (id>=-1)
								{
									// Air or the void are unsealed obviously
									this.sealed = false;
									return;
								}
							} else
								//If there is breatheable air or an unsealed block here, that is also unsealed
								if (id == breatheableAirID || this.canBlockPassAirCheck(id, sideVec, side))
								{
									this.sealed = false;
									return;
								}
						}
					}
				}
			}
			// Is there a further layer of air/permeable blocks to test?
			this.currentLayer = nextLayer;
			nextLayer = new LinkedList<BlockVec3>();
		}
	}

	// Note this will NPE if id==0, so don't call this with id==0
	private boolean canBlockPassAirCheck(int id, BlockVec3 vec, int side)
	{
		Block block = Block.blocksList[id];

		if (block == null)
		{
			FMLLog.info("**** Please report to mod author: BlockPassAirCheck block ID "+id+" was null ****");
			return true;
		}
		//Check leaves first, because their isOpaqueCube() test depends on graphics settings
		//(See net.minecraft.block.BlockLeaves.isOpaqueCube()!)
		if (block instanceof BlockLeavesBase)
		{
			return true;
		}
		
		if (block.isOpaqueCube()) 
		{
			//Gravel, wool and sponge are porous
			if (block instanceof BlockGravel || block.blockMaterial == Material.cloth || block instanceof BlockSponge)
			{
				return true;
			}
			
			return false;
		}
		
		if (block instanceof BlockGlass)
		{
			return false;
		}

		//Solid but non-opaque blocks, for example special glass
		if (OxygenPressureProtocol.nonPermeableBlocks.containsKey(id))
		{	
			ArrayList<Integer> metaList = OxygenPressureProtocol.nonPermeableBlocks.get(id);
			if (metaList.contains(Integer.valueOf(-1)) ||  metaList.contains(vec.getBlockMetadata(this.world)))
			{
				return false;
			}
		}

		if (block instanceof IPartialSealableBlock)
		{
			IPartialSealableBlock blockPartial = (IPartialSealableBlock) block; 
			if (blockPartial.isSealed(this.world, vec.x, vec.y, vec.z, ForgeDirection.getOrientation(side)))
			{
				// If a partial block checks as solid, allow it to be tested
				// again from other directions
				// This won't cause an endless loop, because the block won't
				// be included in nextLayer if it checks as solid
				this.checked.remove(vec);
				this.checkCount--;
				return false;
			}

			//Find the solid sides so they don't get iterated into, when doing the next layer
			for (int i=0;i<6;i++)
			{
				if (i==side) continue;
				if (blockPartial.isSealed(this.world, vec.x, vec.y, vec.z, ForgeDirection.getOrientation(i)))
				{
					vec.setSideDone(i ^ 1);
				}
			}
			return true;
		}

		if (block instanceof GCCoreBlockUnlitTorch)
		{
			this.torchesToUpdate.add(vec);
			return true;
		}

		//Half slab seals on the top side or the bottom side according to its metadata
		if (block instanceof BlockHalfSlab)
        {
            boolean isTopSlab = (vec.getBlockMetadata(this.world) & 8) == 8;
			//Looking down onto a top slab or looking up onto a bottom slab
			if ((side == 0 && isTopSlab) || (side == 1 && !isTopSlab))
            {
            	//Sealed from that solid side but allow other sides still to be checked
    			this.checked.remove(vec);
    			this.checkCount--;
    			return false;		
            }
            //Not sealed
			vec.setSideDone(isTopSlab ? 1:0);
			return true;            	
        }
        
		//Farmland etc only seals on the solid underside
		if (block instanceof BlockFarmland || block instanceof BlockEnchantmentTable || block instanceof BlockFluid)
        {
            if (side==1)
            {
    			//Sealed from the underside but allow other sides still to be checked
            	this.checked.remove(vec);
    			this.checkCount--;
    			return false;		
            }
            //Not sealed
			vec.setSideDone(0);
			return true;            		            	            
        }

		if (block instanceof BlockPistonBase)
		{
			BlockPistonBase piston = (BlockPistonBase)block;
			int meta = vec.getBlockMetadata(this.world);
			if (piston.isExtended(meta))
			{
				int facing = piston.getOrientation(meta);
				if (side==facing)
				{
					this.checked.remove(vec);
					this.checkCount--;
					return false;		
				}
				vec.setSideDone(facing ^ 1);
				return true;
			}
			return false;
		}
		
		//General case - this should cover any block which correctly implements isBlockSolidOnSide
		//including most modded blocks - Forge microblocks in particular is covered by this.
		// ### Any exceptions in mods should implement the IPartialSealableBlock interface ###
		if (block.isBlockSolidOnSide(this.world, vec.x, vec.y, vec.z, ForgeDirection.getOrientation(side ^ 1)))
		{
			//Solid on all sides
			if (block.isBlockNormalCube(this.world, vec.x, vec.y, vec.z)) return false;
			//Sealed from this side but allow other sides still to be checked
			this.checked.remove(vec);
			this.checkCount--;
			return false;		
		}
		
		//Easy case: airblock, return without checking other sides
		if (block.isAirBlock(this.world, vec.x, vec.y, vec.z)) return true;
		
		//Not solid on that side.
		//Look to see if there is any other side which is solid in which case a check will not be needed next time
		for (int i=0;i<6;i++)
		{
			if (i==(side ^ 1)) continue;
			if (block.isBlockSolidOnSide(this.world, vec.x, vec.y, vec.z, ForgeDirection.getOrientation(i)))
			{
				vec.setSideDone(i);
			}
		}
			
		//Not solid from this side, so this is not sealed
		return true;
	}
}