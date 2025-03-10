package net.snakefangox.worldshell.transfer;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.world.World;
import net.snakefangox.worldshell.WorldShell;
import net.snakefangox.worldshell.collision.Matrix3d;
import net.snakefangox.worldshell.entity.WorldShellEntity;
import net.snakefangox.worldshell.storage.Bay;
import net.snakefangox.worldshell.storage.LocalSpace;
import net.snakefangox.worldshell.storage.ShellStorageData;

public final class WorldShellDeconstructor extends ShellTransferOperator {

    private final int shellId;
    private final RotationSolver rotationSolver;
    private final ConflictSolver conflictSolver;
    private final LocalSpace noRotLocalSpace;
    private final Matrix3d rotation;
    private final BlockRotation blockRotation;

    private Stage stage = Stage.SETUP;

    private ShellStorageData shellStorage;
    private Bay bay;
    private World shellWorld;
    private BlockBoxIterator iterator;

    public static WorldShellDeconstructor create(ServerWorld world, int shellId, RotationSolver rotationSolver, ConflictSolver conflictSolver, LocalSpace localSpace) {
        return new WorldShellDeconstructor(world, shellId, rotationSolver, conflictSolver, localSpace);
    }

    public static WorldShellDeconstructor create(WorldShellEntity entity, RotationSolver rotationSolver, ConflictSolver conflictSolver) {
        if (!(entity.world instanceof ServerWorld))
            throw new RuntimeException("Trying to create WorldShellDeconstructor on client");
        return new WorldShellDeconstructor((ServerWorld) entity.world, entity.getShellId(), rotationSolver, conflictSolver,
                LocalSpace.of(entity.getLocalX(), entity.getLocalY(), entity.getLocalZ()));
    }

    private WorldShellDeconstructor(ServerWorld world, int shellId, RotationSolver rotationSolver, ConflictSolver conflictSolver, LocalSpace localSpace) {
        super(world);
        this.shellId = shellId;
        this.rotationSolver = rotationSolver;
        this.conflictSolver = conflictSolver;
        this.noRotLocalSpace = LocalSpace.of(localSpace.getLocalX(), localSpace.getLocalY(), localSpace.getLocalZ());
        this.rotation = localSpace.getInverseRotationMatrix();
        this.blockRotation = BlockRotation.NONE;
    }

    @Override
    public boolean isFinished() {
        return stage == Stage.FINISHED;
    }

    public boolean isRemoving() {
        return stage == Stage.REMOVE;
    }

    @Override
    protected LocalSpace getLocalSpace() {
        return noRotLocalSpace;
    }

    @Override
    protected LocalSpace getRemoteSpace() {
        return bay;
    }

    @Override
    public void performPass() {
        switch (stage) {
            case SETUP:
                setup();
                break;
            case PLACE:
                place();
                break;
            case REMOVE:
                remove();
                break;
        }
    }

    private void setup() {
        shellStorage = ShellStorageData.getOrCreate(getWorld());
        bay = shellStorage.getBay(shellId);
        shellWorld = WorldShell.getStorageDim(getWorld().getServer());
        iterator = new BlockBoxIterator(bay.getBounds());
        stage = Stage.PLACE;
    }

    private void place() {
        int i = 0;
        while (iterator.hasNext() && i < MAX_OPS) {
            transferBlock(shellWorld, getWorld(), iterator.next(), false, rotationSolver, rotation, blockRotation, conflictSolver);
            ++i;
        }
        if (!iterator.hasNext()) stage = Stage.REMOVE;
    }

    private void remove() {
        shellStorage.freeBay(shellId, this);
        stage = Stage.FINISHED;
    }

    private enum Stage {
        SETUP, PLACE, REMOVE, FINISHED
    }
}
