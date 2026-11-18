package dev.yard.site.view;

import net.minecraft.resources.ResourceLocation;

/** 一个群系：id 用来查中文名，rgb 是沙盘上的颜色（0xRRGGBB）。 */
record BiomeInfo(ResourceLocation id, int rgb) {}
