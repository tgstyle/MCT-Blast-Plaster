package mctmods.blastplaster.helper;

import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Objects;

public abstract class TickingLinkedList<T> {
    protected LinkedList<TickContainer<T>> queue = new LinkedList<>();

    public T processTick() {
        if (this.queue.isEmpty()) { return null; }
        TickContainer<T> current = this.queue.getFirst();
        current.decrement();
        if (current.isDone()) { return Objects.requireNonNull(this.queue.poll()).getValue(); }
        return null;
    }

    public void enqueue(int ticks, T value) {
        this.enqueue(new TickContainer<>(ticks, value));
    }

    protected void enqueue(TickContainer<T> toAdd) {
        if (this.queue.isEmpty()) {
            this.queue.add(toAdd);
            return;
        }
        ListIterator<TickContainer<T>> iterator = this.queue.listIterator();
        while (iterator.hasNext()) {
            TickContainer<T> existing = iterator.next();
            int comparison = existing.compareTo(toAdd);
            if (comparison >= 0) {
                if (comparison == 0) {
                    existing.setValue(this.combine(toAdd.getValue(), existing.getValue()));
                    return;
                }
                iterator.set(toAdd);
                existing.subtract(toAdd);
                iterator.add(existing);
                return;
            }
            toAdd.subtract(existing);
        }
        this.queue.add(toAdd);
    }

    protected abstract T combine(T first, T second);
}
