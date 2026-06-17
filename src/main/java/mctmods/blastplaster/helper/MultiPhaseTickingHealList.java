package mctmods.blastplaster.helper;

import java.util.Collection;
import java.util.LinkedList;

public class MultiPhaseTickingHealList {
    public enum HealPhase { GROUND, TREE, FLORA }

    private final TickingHealList ground = new TickingHealList();
    private final TickingHealList tree = new TickingHealList();
    private final TickingHealList flora = new TickingHealList();

    public void enqueue(HealPhase phase, int ticks, BlockStatePosWrapper single) {
        switch (phase) {
            case GROUND -> ground.enqueue(ticks, single);
            case TREE -> tree.enqueue(ticks, single);
            case FLORA -> flora.enqueue(ticks, single);
        }
    }

    public Collection<BlockStatePosWrapper> processTick() {
        Collection<BlockStatePosWrapper> result = ground.processTick();
        if (result != null) { return result; }
        result = tree.processTick();
        if (result != null) { return result; }
        return flora.processTick();
    }

    public LinkedList<TickContainer<Collection<BlockStatePosWrapper>>> getQueue() {
        LinkedList<TickContainer<Collection<BlockStatePosWrapper>>> combined = new LinkedList<>();
        combined.addAll(ground.getQueue());
        combined.addAll(tree.getQueue());
        combined.addAll(flora.getQueue());
        return combined;
    }

    public boolean isEmpty() {
        return ground.getQueue().isEmpty() && tree.getQueue().isEmpty() && flora.getQueue().isEmpty();
    }
}
