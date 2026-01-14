package mctmods.blastplaster.helper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;

public class TickingHealList extends TickingLinkedList<Collection<BlockStatePosWrapper>> {
    @Override
    protected Collection<BlockStatePosWrapper> combine(Collection<BlockStatePosWrapper> first, Collection<BlockStatePosWrapper> second) {
        second.addAll(first);
        return second;
    }

    public LinkedList<TickContainer<Collection<BlockStatePosWrapper>>> getQueue() {
        return this.queue;
    }

    public void enqueue(int ticks, BlockStatePosWrapper single) {
        Collection<BlockStatePosWrapper> coll = new ArrayList<>(1);
        coll.add(single);
        this.enqueue(ticks, coll);
    }
}
