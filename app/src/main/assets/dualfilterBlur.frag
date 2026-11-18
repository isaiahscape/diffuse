#ifdef GL_ES
precision mediump float;
#endif

uniform sampler2D u_texture;
uniform float u_scaleLevel;
uniform vec2 u_resolution;
uniform int u_scaleType;

varying vec4 v_color;
varying vec2 v_texCoord;

vec4 downsample(sampler2D tex, vec2 uuvv)
{
    // uuvv < uv
    // 1 level = 1 -> 1/1 scaled to topleft 1/1 (1/1*1/1) uv actually used (0-1) uuvv actually used (0-1)
    // 2 level = 2 -> 1/1 scaled to topleft 1/4 (1/2*1/2) uv actually used (0-1) uuvv actually used (0-0.5)
    // 3 level = 4 -> 1/4 scaled to topleft 1/16 (1/4*1/4) uv actually used (0-0.5) uuvv actually used (0-0.25)
    if (uuvv.x > 1.0 / u_scaleLevel || uuvv.y > 1.0 / u_scaleLevel)
    {
        return vec4(0.0);
    }
    vec2 uv = uuvv * u_scaleLevel;
    vec2 halfpixel = 1.0 * u_scaleLevel / u_resolution;
    vec4 sum = texture2D(tex, uv) * 4.0;
    sum += texture2D(tex, uv - halfpixel.xy);
    sum += texture2D(tex, uv + halfpixel.xy);
    sum += texture2D(tex, uv + vec2(halfpixel.x, -halfpixel.y));
    sum += texture2D(tex, uv - vec2(halfpixel.x, -halfpixel.y));
    return sum / 8.0;
}

vec4 upsample(sampler2D tex, vec2 uuvv)
{
    // uuvv > uv
    // 1 level = 1 -> topleft 1/1 scaled to 1/1 uv actually used (0-1) uuvv actually used (0-1)
    // 2 level = 2 -> topleft 1/4 scaled to 1/1 uv actually used (0-0.5) uuvv actually used(0-1)
    // 3 level = 4 -> topleft 1/16 scaled to left 1/4 uv actually used (0-0.25) uuvv actually used (0-0.5)
    vec2 uv = uuvv / u_scaleLevel;
    if (uv.x > 1.0 / u_scaleLevel || uv.y > 1.0 / u_scaleLevel)
    {
        return vec4(0.0);
    }
    vec2 halfpixel = 1.0 / u_scaleLevel / u_resolution;
    vec4 sum = texture2D(tex, uv + vec2(-halfpixel.x * 2.0, 0.0));
    sum += texture2D(tex, uv + vec2(-halfpixel.x, halfpixel.y)) * 2.0;
    sum += texture2D(tex, uv + vec2(0.0, halfpixel.y * 2.0));
    sum += texture2D(tex, uv + vec2(halfpixel.x, halfpixel.y)) * 2.0;
    sum += texture2D(tex, uv + vec2(halfpixel.x * 2.0, 0.0));
    sum += texture2D(tex, uv + vec2(halfpixel.x, -halfpixel.y)) * 2.0;
    sum += texture2D(tex, uv + vec2(0.0, -halfpixel.y * 2.0));
    sum += texture2D(tex, uv + vec2(-halfpixel.x, -halfpixel.y)) * 2.0;
    return sum / 12.0;
}

void main() {
    if (u_scaleType == -1)
    {
        gl_FragColor = downsample(u_texture, v_texCoord);
    }else
    {
//        gl_FragColor = texture2D(u_texture, v_texCoord);
        gl_FragColor = upsample(u_texture, v_texCoord);
    }
}