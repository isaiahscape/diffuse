#ifdef GL_ES
precision mediump float;
#endif

uniform sampler2D u_texture;
uniform int u_level;
uniform vec2 u_resolution;
uniform float u_brightnessOffset;
uniform float u_saturation;
uniform int u_hdrEnabled;
uniform float u_hdrIntensity;
uniform int u_isFinalPass;

varying vec4 v_color;
varying vec2 v_texCoord;

vec4 applyOutputGrading(vec4 c) {
    if (u_isFinalPass == 0) {
        return c;
    }
    vec3 rgb = max(c.rgb, vec3(0.0));
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = max(vec3(0.0), mix(vec3(luma), rgb, u_saturation));
    if (u_hdrEnabled != 0) {
        vec3 lin = pow(rgb, vec3(2.2));
        float linLuma = dot(lin, vec3(0.2126, 0.7152, 0.0722));
        float boost = 1.0 + max(0.0, u_hdrIntensity - 1.0) * 0.55 * pow(clamp(linLuma, 0.0, 1.0), 1.25);
        vec3 hdr = lin * (0.92 + 0.35 * u_hdrIntensity) * boost;
        hdr = (hdr / (hdr + vec3(0.65))) * 1.65;
        rgb = pow(clamp(hdr, 0.0, 1.0), vec3(1.0 / 2.2));
    }
    return vec4(rgb, c.a);
}

vec4 reSample(sampler2D texture, vec2 res, in int d, in vec2 uv)
{
    vec2 step1 = (vec2(d) + 0.5) / res;
    vec4 color = vec4(0.0);
    color += texture2D(texture, uv + step1) / float(4);
    color += texture2D(texture,  uv - step1) / float(4);
    vec2 step2 = step1;
    step2.x = -step2.x;
    color += texture2D(texture, uv + step2) / float(4);
    color += texture2D(texture,  uv - step2) / float(4);
    color.xyz *= (1.0 + u_brightnessOffset);
    return color;
}

void main() {
    gl_FragColor = applyOutputGrading(reSample(u_texture, u_resolution, u_level, v_texCoord));
}
