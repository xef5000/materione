#version 150

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

#moj_import <minecraft:materione_text_effects.glsl>

void main() {
    // attributes are read-only: the hooks animate a local copy
    vec3 mtrPosition = Position;
    materionePreTransform(mtrPosition);

    gl_Position = ProjMat * ModelViewMat * vec4(mtrPosition, 1.0);

    sphericalVertexDistance = fog_spherical_distance(mtrPosition);
    cylindricalVertexDistance = fog_cylindrical_distance(mtrPosition);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;

    materionePostTransform(mtrPosition);
}
