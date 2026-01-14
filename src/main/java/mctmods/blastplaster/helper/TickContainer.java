package mctmods.blastplaster.helper;

public class TickContainer<T> extends TickComparable {
    private T value;

    public TickContainer(int ticks, T value) {
        super(ticks);
        this.value = value;
    }

    public T getValue() {
        return this.value;
    }

    public void setValue(T value) {
        this.value = value;
    }
}
