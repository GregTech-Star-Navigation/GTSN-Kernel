package com.gtsn.kernel.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link Region} 的最小实现（二期）。
 *
 * <p>只持有状态，不做任何调度决策 —— 调度在 {@link SerialRegionScheduler}。</p>
 */
public final class SimpleRegion implements Region {

    private final RegionId id;
    private final List<Provenance> provenance = new ArrayList<>();
    private Thread owner;
    private int chunkCount;
    private boolean running;

    public SimpleRegion(RegionId id, String reason) {
        this.id = Objects.requireNonNull(id, "id");
        this.provenance.add(new Provenance(reason, false));
    }

    @Override
    public RegionId id() {
        return id;
    }

    @Override
    public Thread owner() {
        return owner;
    }

    @Override
    public int chunkCount() {
        return chunkCount;
    }

    @Override
    public boolean running() {
        return running;
    }

    @Override
    public List<Provenance> provenance() {
        return List.copyOf(provenance);
    }

    public void bindOwner(Thread thread) {
        this.owner = thread;
    }

    public void chunkCount(int value) {
        this.chunkCount = Math.max(0, value);
    }

    public void running(boolean value) {
        this.running = value;
    }

    /** 记录一次合并来源（合并后新的代表 region 会多一条 provenance）。 */
    public void addProvenance(String reason, boolean merged) {
        provenance.add(new Provenance(reason, merged));
    }
}
