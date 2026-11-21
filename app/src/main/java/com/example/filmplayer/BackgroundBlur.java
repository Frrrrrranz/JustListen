package com.example.filmplayer;

final class BackgroundBlur {
    private BackgroundBlur() {
    }

    static int[] apply(int[] pixels, int width, int height, int radius) {
        if (width <= 0 || height <= 0 || pixels.length != width * height || radius < 0) {
            throw new IllegalArgumentException("Invalid blur dimensions");
        }
        if (radius == 0) {
            return pixels.clone();
        }
        // 背景先填成不透明暖白色，RGB 平均时不会产生透明边缘的黑边。
        int[] horizontal = new int[pixels.length];
        int[] result = new int[pixels.length];
        int count = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int offset = -radius; offset <= radius; offset++) {
                int color = pixels[y * width + clamp(offset, width)];
                red += color >> 16 & 255;
                green += color >> 8 & 255;
                blue += color & 255;
            }
            for (int x = 0; x < width; x++) {
                horizontal[y * width + x] = pack(red / count, green / count, blue / count);
                int removed = pixels[y * width + clamp(x - radius, width)];
                int added = pixels[y * width + clamp(x + radius + 1, width)];
                red += (added >> 16 & 255) - (removed >> 16 & 255);
                green += (added >> 8 & 255) - (removed >> 8 & 255);
                blue += (added & 255) - (removed & 255);
            }
        }
        for (int x = 0; x < width; x++) {
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int offset = -radius; offset <= radius; offset++) {
                int color = horizontal[clamp(offset, height) * width + x];
                red += color >> 16 & 255;
                green += color >> 8 & 255;
                blue += color & 255;
            }
            for (int y = 0; y < height; y++) {
                result[y * width + x] = pack(red / count, green / count, blue / count);
                int removed = horizontal[clamp(y - radius, height) * width + x];
                int added = horizontal[clamp(y + radius + 1, height) * width + x];
                red += (added >> 16 & 255) - (removed >> 16 & 255);
                green += (added >> 8 & 255) - (removed >> 8 & 255);
                blue += (added & 255) - (removed & 255);
            }
        }
        return result;
    }

    private static int clamp(int value, int limit) {
        return Math.max(0, Math.min(value, limit - 1));
    }

    private static int pack(int red, int green, int blue) {
        return 0xff000000 | red << 16 | green << 8 | blue;
    }
}
