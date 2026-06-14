#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// P1 closest-hit. Resolves block albedo (atlas * tint) and the geometric normal, then writes them
// plus the hit distance into the payload. Lighting is done in raygen (deferred), so this shader does
// no shading or secondary tracing. Per-primitive {normal, tint} (gl_PrimitiveID) + per-vertex atlas
// UVs (index buffer -> UV buffer, barycentric-interpolated). textureLod 0 since rays have no
// derivatives (ray-cone LOD is a later optimization).
struct Prim {
    vec4 normal;
    vec4 tint;
};

layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Prims { Prim p[]; };
layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer Indices { uint i[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer UVs { vec2 uv[]; };

layout(binding = 2, set = 0) uniform sampler2D blockAtlas;

layout(push_constant) uniform Push {
    mat4 invViewProj;
    vec3 camOffset;
    uint64_t primAddr;
    uint64_t idxAddr;
    uint64_t uvAddr;
} pc;

struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
};
layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 attribs;

void main() {
    uint pid = gl_PrimitiveID;
    Prim pr = Prims(pc.primAddr).p[pid];
    vec3 n = normalize(pr.normal.xyz);
    vec3 tint = pr.tint.rgb;

    Indices indices = Indices(pc.idxAddr);
    UVs uvs = UVs(pc.uvAddr);
    uint i0 = indices.i[3u * pid + 0u];
    uint i1 = indices.i[3u * pid + 1u];
    uint i2 = indices.i[3u * pid + 2u];
    vec3 bary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    vec2 uv = bary.x * uvs.uv[i0] + bary.y * uvs.uv[i1] + bary.z * uvs.uv[i2];

    // Orient the normal toward the viewer so the AO/shadow offset and N·L stay correct even if a
    // back-face is hit.
    if (dot(n, gl_WorldRayDirectionEXT) > 0.0) {
        n = -n;
    }

    payload.albedo = textureLod(blockAtlas, uv, 0.0).rgb * tint;
    payload.normal = n;
    payload.hitT = gl_HitTEXT;
}
