#ifdef GL_ES
precision highp float;
#endif

uniform sampler2D u_texture;
uniform float u_time;
uniform vec2 u_resolution;

varying vec4 v_color;
varying vec2 v_texCoord;

void main()
{
    vec4 color = texture2D(u_texture, v_texCoord);
    if ((v_texCoord.x - 0.5 > 0.49 || v_texCoord.x - 0.5 < -0.49) || (v_texCoord.y - 0.5 > 0.49 || v_texCoord.y - 0.5 < -0.49))
    {
        color = vec4(1,1,1,1);
    }
    gl_FragColor = color;
}