package com.example.filmplayer;

final class DiscCarouselState {
    private static final int VISIBLE_COUNT = 5;
    private static final int CENTER_POSITION = 2;

    private int slotCount;
    private float position;
    private boolean interactionPending;

    DiscCarouselState(int slotCount) {
        setSlotCount(slotCount);
    }

    void setSlotCount(int newSlotCount) {
        if (newSlotCount < 1) {
            throw new IllegalArgumentException("At least one slot is required");
        }
        slotCount = newSlotCount;
        setPosition(position);
    }

    int[] getVisibleSlotIndices() {
        int[] visibleSlots = new int[VISIBLE_COUNT];
        for (int position = 0; position < VISIBLE_COUNT; position++) {
            //前后都按当前歌曲数循环，滑过头尾也能接上
            visibleSlots[position] = getSlotIndexAtOffset(position - CENTER_POSITION);
        }
        return visibleSlots;
    }

    void selectSlot(int slotIndex) {
        cancelInteraction();
        position = Math.floorMod(slotIndex, slotCount);
    }

    void beginInteraction() {
        interactionPending = true;
    }

    void cancelInteraction() {
        interactionPending = false;
    }

    int takeSettledSlot() {
        if (!interactionPending || position != Math.round(position)) {
            return -1;
        }
        interactionPending = false;
        return getSlotIndexAtOffset(0);
    }

    void moveBy(float slotDistance) {
        setPosition(position + slotDistance);
    }

    void setPosition(float newPosition) {
        if (!Float.isFinite(newPosition)) {
            return;
        }
        //位置始终落在队列范围内，反向滑过第一张也能接到最后一张
        position = newPosition % slotCount;
        if (position < 0) {
            position += slotCount;
        }
    }

    float getSnapTarget() {
        //保留队列长度作为动画终点，末尾能顺着原方向滑到第 1 张
        return Math.round(position);
    }

    float getPosition() {
        return position;
    }

    int getSlotIndexAtOffset(int offset) {
        return Math.floorMod((int) position + offset, slotCount);
    }

    float getRelativePosition(int offset) {
        return offset - (position - (int) position);
    }
}
