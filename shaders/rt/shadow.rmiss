#version 460
#extension GL_EXT_ray_tracing : require

// Shadow / sky-visibility miss (SBT miss index 1). Secondary rays are traced with
// TerminateOnFirstHit | SkipClosestHit, so reaching this shader means the ray escaped without
// hitting geometry -> the surface point is visible to the sun (or to open sky for AO). The caller
// pre-initialises shadowVis to 0.0 (occluded), so we only need to flip it to 1.0 here.
layout(location = 1) rayPayloadInEXT float shadowVis;

void main() {
    shadowVis = 1.0;
}
