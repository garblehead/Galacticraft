package micdoodle8.mods.galacticraft.moon.wgen.dungeon;

import java.util.ArrayList;
import java.util.Random;

import net.minecraft.world.World;

public abstract class GCDungeonRoom {
	
	public World worldObj;
	
	//Min Corner, Lower, Left, Back
	public int posX;
	public int posY;
	public int posZ;
	
	//East = 0, North = 1, South = 2, West = 3, Up = 4, Down = 5.
	//North is z++, East is x++.
	public int entranceDir;
	
	private static ArrayList<GCDungeonRoom> rooms = new ArrayList<GCDungeonRoom>();
	private static ArrayList<GCDungeonRoom> bossRooms = new ArrayList<GCDungeonRoom>();
	private static ArrayList<GCDungeonRoom> treasureRooms = new ArrayList<GCDungeonRoom>();
	
	public GCDungeonRoom(World worldObj, int posX, int posY, int posZ, int entranceDir)
	{
		this.worldObj = worldObj;
		this.posX = posX;
		this.posY = posY;
		this.posZ = posZ;
		this.entranceDir = entranceDir;
	}
	
	public abstract void generate(int[] chunk, int[] meta, int cx, int cz);
	
	public abstract GCDungeonBoundingBox getBoundingBox();
	
	protected abstract GCDungeonRoom makeRoom(World worldObj, int x, int y, int z, int dir);
	
	protected abstract void handleTileEntities(Random rand);
	
	public static GCDungeonRoom makeRoom(World worldObj, Random rand, int x, int y, int z, int dir)
	{
		return rooms.get(rand.nextInt(rooms.size())).makeRoom(worldObj, x, y, z, dir);
	}
	
	public static GCDungeonRoom makeBossRoom(World worldObj, Random rand, int x, int y, int z, int dir)
	{
		return bossRooms.get(rand.nextInt(bossRooms.size())).makeRoom(worldObj, x, y, z, dir);
	}
	
	public static GCDungeonRoom makeTreasureRoom(World worldObj, Random rand, int x, int y, int z, int dir)
	{
		return treasureRooms.get(rand.nextInt(treasureRooms.size())).makeRoom(worldObj, x, y, z, dir);
	}
	
	protected boolean placeBlock(int[] blocks, int[] metas, int x, int y, int z, int cx, int cz, int id, int meta)
	{
		if (GCMapGenDungeon.useArrays)
		{
			cx *= 16;
			cz *= 16;
			x -= cx;
			z -= cz;
			if(x < 0 || x >= 16 || z < 0 || z >= 16)
			{
				return false;
			}
			int index = getIndex(x, y, z);
			blocks[index] = id;
			metas[index] = meta;
		}
		else
		{
			worldObj.setBlock(x, y, z, id, meta, 3);
		}
		return true;
	}
	
	private int getIndex(int x, int y, int z)
	{
		return (x * 16 + z) * 128 + y;
	}
	
	static
	{
		rooms.add(new GCRoomEmpty(null, 0, 0, 0, 0));
		rooms.add(new GCRoomSpawner(null, 0, 0, 0, 0));
		rooms.add(new GCRoomSpawner(null, 0, 0, 0, 0));
		rooms.add(new GCRoomSpawner(null, 0, 0, 0, 0));
		rooms.add(new GCRoomSpawner(null, 0, 0, 0, 0));
		rooms.add(new GCRoomSpawner(null, 0, 0, 0, 0));
		rooms.add(new GCRoomSpawner(null, 0, 0, 0, 0));
		rooms.add(new GCRoomSpawner(null, 0, 0, 0, 0));
		rooms.add(new GCRoomSpawner(null, 0, 0, 0, 0));
		rooms.add(new GCRoomChests(null, 0, 0, 0, 0));
		rooms.add(new GCRoomChests(null, 0, 0, 0, 0));
		bossRooms.add(new GCRoomBoss(null, 0, 0, 0, 0));
		treasureRooms.add(new GCRoomTreasure(null, 0, 0, 0, 0));
	}
}
