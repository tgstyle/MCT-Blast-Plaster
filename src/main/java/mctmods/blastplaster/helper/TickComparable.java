package mctmods.blastplaster.helper;

public class TickComparable implements Comparable<TickComparable> {
    private int ticks;

    public TickComparable(int ticks) {
        this.ticks = ticks;
    }

    @Override
    public int compareTo(TickComparable other) {
        return Integer.compare(this.ticks, other.ticks);
    }

    public void decrement() {
        this.ticks--;
    }

    public boolean isDone() {
        return this.ticks <= 0;
    }

    public void subtract(TickComparable other) {
        this.ticks -= other.ticks;
    }

    public int getTicks() {
        return this.ticks;
    }
}
