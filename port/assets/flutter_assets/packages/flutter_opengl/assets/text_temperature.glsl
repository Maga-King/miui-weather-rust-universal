#version 300 es
precision lowp float;
in highp vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTex;
uniform sampler2D uBg;// add  温度
uniform highp vec2 uTexRes;
uniform sampler2D uNoise;

//uniform vec4 uColPos1;
//uniform vec4 uColPos2;
//uniform vec4 uColPos3;
//uniform vec4 uColPos4;
//uniform vec4 uColPos5;

uniform vec4 uBlendColor;
uniform float uLab;

uniform float uAlpha;
uniform float uWhiteAlpha;
uniform bool uShouldJitter;

//uniform float uClipRate;
//uniform vec2 uResolution;
//uniform float uGradientScale;// add  温度
uniform float uUseAlphaX;// add   前面背景
uniform float uAlphaX1;// add   前面背景
uniform float uAlphaX2;// add   前面背景

const int BLENDMODE_SRC_OVER = 0;
const int BLENDMODE_COLOR_BURN = 1;
const int BLENDMODE_COLOR_DODGE = 2;

const int BLENDMODE_LINEAR_LIGHT = 100;
const int BLENDMODE_LINEAR_LIGHT_WITH_GREYSCALE = 101;

const int BLENDMODE_LAB_LIGHTEN = 102;
const int BLENDMODE_LAB_LIGHTEN_WITH_GREYSCALE = 103;

const int BLENDMODE_LAB_DARKEN = 104;
const int BLENDMODE_LAB_DARKEN_WITH_GREYSCALE = 105;

const float JITTER_STRENGTH = 0.015;

/*
* Src Over
*/
vec4 blendSrcOver(vec4 src, vec4 dst) {
    if(src.a == 0.0) {
        return dst;
    }

    float srcAlpha = src.a;
    float dstAlpha = dst.a * (1.0 - srcAlpha);
    float outAlpha = srcAlpha + dstAlpha;

    if(outAlpha == 0.0) {
        return vec4(0, 0, 0, 0);
    }

    vec4 outColor = (src * srcAlpha + dst * dstAlpha) / outAlpha;
    return vec4(outColor.rgb, outAlpha);
}

/*
* Color Burn
*/
float blendColorBurn(float base, float blend) {
    return (blend == 0.0) ? blend : max((1.0 - ((1.0 - base) / blend)), 0.0);
}

vec3 blendColorBurn(vec3 base, vec3 blend) {
    return vec3(blendColorBurn(base.r, blend.r), blendColorBurn(base.g, blend.g), blendColorBurn(base.b, blend.b));
}

vec3 blendColorBurn(vec3 base, vec3 blend, float opacity) {
    return (blendColorBurn(base, blend) * opacity + base * (1.0 - opacity));
}

vec4 blendColorBurn(vec4 blend, vec4 base) {
    vec3 baseOver = blendColorBurn(base.rgb, blend.rgb, blend.a);
    float alpha = base.a;
    return vec4(baseOver, alpha);
}

/**
* Color Dodge
*/
float blendColorDodge(float base, float blend) {
    return (blend == 1.0) ? blend : min(base / (1.0 - blend), 1.0);
}

vec3 blendColorDodge(vec3 base, vec3 blend) {
    return vec3(blendColorDodge(base.r, blend.r), blendColorDodge(base.g, blend.g), blendColorDodge(base.b, blend.b));
}

vec3 blendColorDodge(vec3 base, vec3 blend, float opacity) {
    return (blendColorDodge(base, blend) * opacity + base * (1.0 - opacity));
}

vec4 blendColorDodge(vec4 blend, vec4 base) {
    vec3 baseOver = blendColorDodge(base.rgb, blend.rgb, blend.a);
    float alpha = base.a;
    return vec4(baseOver, alpha);
}

/**
* Linear Dodge
*/
float blendLinearDodge(float base, float blend) {
    // Note : Same implementation as BlendAddf
    return min(base + blend, 1.0);
}

vec3 blendLinearDodge(vec3 base, vec3 blend) {
    // Note : Same implementation as BlendAdd
    return min(base + blend, vec3(1.0));
}

vec3 blendLinearDodge(vec3 base, vec3 blend, float opacity) {
    return (blendLinearDodge(base, blend) * opacity + base * (1.0 - opacity));
}

/**
* Linear Burn
*/
float blendLinearBurn(float base, float blend) {
    // Note : Same implementation as BlendSubtractf
    return max(base + blend - 1.0, 0.0);
}

vec3 blendLinearBurn(vec3 base, vec3 blend) {
    // Note : Same implementation as BlendSubtract
    return max(base + blend - vec3(1.0), vec3(0.0));
}

vec3 blendLinearBurn(vec3 base, vec3 blend, float opacity) {
    return (blendLinearBurn(base, blend) * opacity + base * (1.0 - opacity));
}

/**
* Linear Burn
*/
float blendLinearLight(float base, float blend) {
    return blend < 0.5 ? blendLinearBurn(base, (2.0 * blend)) : blendLinearDodge(base, (2.0 * (blend - 0.5)));
}

vec3 blendLinearLight(vec3 base, vec3 blend) {
    return vec3(blendLinearLight(base.r, blend.r), blendLinearLight(base.g, blend.g), blendLinearLight(base.b, blend.b));
}

vec3 blendLinearLight(vec3 base, vec3 blend, float opacity) {
    return (blendLinearLight(base, blend) * opacity + base * (1.0 - opacity));
}

vec4 blendLinearLight(vec4 blend, vec4 base) {
    vec3 baseOver = blendLinearLight(base.rgb, blend.rgb, blend.a);
    float alpha = base.a;
    return vec4(baseOver, alpha);
}

float greyscale(vec3 color) {
    return 0.3 * color.r + 0.59 * color.g + 0.11 * color.b;
}

/**
* Lab Lighten & Darken
*/
vec3 rgb2xyz(vec3 color) {

    if(color.r > 0.04045) {
        color.r = pow(((color.r + 0.055) / 1.055), 2.4);
    } else {
        color.r = color.r / 12.92;
    }

    if(color.g > 0.04045) {
        color.g = pow(((color.g + 0.055) / 1.055), 2.4);
    } else {
        color.g = color.g / 12.92;
    }

    if(color.b > 0.04045) {
        color.b = pow(((color.b + 0.055) / 1.055), 2.4);
    } else {
        color.b = color.b / 12.92;
    }

    color = color * 100.0;

    vec3 xyz;
    xyz.x = color.r * 0.4124 + color.g * 0.3576 + color.b * 0.1805;
    xyz.y = color.r * 0.2126 + color.g * 0.7152 + color.b * 0.0722;
    xyz.z = color.r * 0.0193 + color.g * 0.1192 + color.b * 0.9505;

    return xyz;
}

vec3 xyz2lab(vec3 color) {
    vec3 white = vec3(95.047, 100.000, 108.883);
    color = color / white;

    if(color.x > 0.008856) {
        color.x = pow(color.x, 1.0 / 3.0);
    } else {
        color.x = (7.787 * color.x) + (16.0 / 116.0);
    }

    if(color.y > 0.008856) {
        color.y = pow(color.y, 1.0 / 3.0);
    } else {
        color.y = (7.787 * color.y) + (16.0 / 116.0);
    }

    if(color.z > 0.008856) {
        color.z = pow(color.z, 1.0 / 3.0);
    } else {
        color.z = (7.787 * color.z) + (16.0 / 116.0);
    }

    vec3 lab;
    lab.x = (116.0 * color.y) - 16.0;
    lab.y = 500.0 * (color.x - color.y);
    lab.z = 200.0 * (color.y - color.z);

    return lab;
}

vec3 rgb2lab(vec3 color) {
    return xyz2lab(rgb2xyz(color));
}

vec3 lab2xyz(vec3 lab) {
    float y = (lab.x + 16.0) / 116.0;
    float x = lab.y / 500.0 + y;
    float z = y - lab.z / 200.0;

    if(pow(y, 3.0) > 0.008856) {
        y = pow(y, 3.0);
    } else {
        y = (y - 16.0 / 116.0) / 7.787;
    }

    if(pow(x, 3.0) > 0.008856) {
        x = pow(x, 3.0);
    } else {
        x = (x - 16.0 / 116.0) / 7.787;
    }

    if(pow(z, 3.0) > 0.008856) {
        z = pow(z, 3.0);
    } else {
        z = (z - 16.0 / 116.0) / 7.787;
    }

    vec3 white = vec3(95.047, 100.000, 108.883);
    vec3 xyz = vec3(x * white.x, y * white.y, z * white.z);

    return xyz;
}

vec3 xyz2rgb(vec3 xyz) {
    xyz = xyz / 100.0;

    float r = xyz.x * 3.2406 + xyz.y * -1.5372 + xyz.z * -0.4986;
    float g = xyz.x * -0.9689 + xyz.y * 1.8758 + xyz.z * 0.0415;
    float b = xyz.x * 0.0557 + xyz.y * -0.2040 + xyz.z * 1.0570;

    if(r > 0.0031308) {
        r = 1.055 * pow(r, (1.0 / 2.4)) - 0.055;
    } else {
        r = 12.92 * r;
    }

    if(g > 0.0031308) {
        g = 1.055 * pow(g, (1.0 / 2.4)) - 0.055;
    } else {
        g = 12.92 * g;
    }

    if(b > 0.0031308) {
        b = 1.055 * pow(b, (1.0 / 2.4)) - 0.055;
    } else {
        b = 12.92 * b;
    }

    r = clamp(r, 0.0, 1.0);
    g = clamp(g, 0.0, 1.0);
    b = clamp(b, 0.0, 1.0);

    return vec3(r, g, b);
}

vec3 lab2rgb(vec3 lab) {
    return xyz2rgb(lab2xyz(lab));
}

vec4 labLighten(vec4 color, float a) {
    vec3 lab = rgb2lab(color.rgb);
    lab.r = (100. - a) / 55. * lab.r + (100. * a - 4500.) / 55.;
    return vec4(lab2rgb(lab), color.a);
}

vec4 labDarken(vec4 color, float a) {
    vec3 lab = rgb2lab(color.rgb);
    lab.r = a / 45. * lab.r;
    return vec4(lab2rgb(lab), color.a);
}

vec3 getCol(vec3 col1, vec3 col2, float rate) {
    vec3 lab = mix(col1, col2, rate);
    return lab;
}

vec3 gammaCorrect(vec3 col, float gamma) {
    return pow(col, vec3(gamma));
}

float brightness(vec3 normPos, vec3 lightPos, float intensity) {

    float dist = length(lightPos);
    float strength = intensity / (dist * dist);

    vec3 normal = normalize(vec3(0.,0.,1.));

    vec3 pos = vec3((vUv * 2. - 1.) * vec2(1.080, 2.4), 0.);
    pos = vec3(pos.x, -pos.z, pos.y);

    vec3 cam = vec3(0., 0., 0.6928);
    cam = vec3(cam.x, -cam.z, cam.y);

    vec3 lightDirection = normalize(-lightPos - pos);
    vec3 eyeVec = normalize(cam - pos);

    // float diffuse = max(dot(normal, lightDirection), 0.0);

    vec3 R = reflect(-lightDirection, normal);
    float specular = pow(max(dot(R, eyeVec), 0.0), 32.0);

    return clamp((specular) * strength, 0., 1.);
}

float noise(vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898,78.233))) * 43758.5453);
}

vec4 textureBlur(sampler2D tex, highp vec2 uv, highp float blurRadius) {
    highp vec2 texCoord = uv * uTexRes;
    highp vec2 offset = vec2(blurRadius);
    vec4 color = vec4(0.0);
    for (float x = -1.; x <= 1.; x++) {
        for (float y = -1.; y <= 1.; y++) {
            color += texture(tex, (texCoord + vec2(x, y) * offset) / uTexRes);
        }
    }
    return color / 9.0;
}

void main() {
    vec3 col = vec3(vUv.y);
    vec2 texUv = vec2(0.5, vUv.y);
    col = texture(uBg, texUv).rgb;
    float n = noise(vUv * 10.0);  // 缩放噪声频率
    col = mix(col, col + vec3(1. / 255.), n);  // 通过噪声平滑渐变
    highp vec2 tempUv = vUv;
    tempUv.y = 1. - tempUv.y;
    //解决上划模糊时文字抖动问题
    if (uShouldJitter) { //不为0时
        highp float noiseValue = texture(uNoise, tempUv).r;
        highp vec2 offset = vec2(0.0, (noiseValue - 0.5) * JITTER_STRENGTH); //y方向偏移
        tempUv += offset;
    }
    vec4 tex = textureBlur(uTex, tempUv, (1.-uAlpha) * 5.);
    col = blendLinearLight(uBlendColor, vec4(col, 1.)).rgb;
    col = labLighten(vec4(col, 1.), 0.6 * uLab * 100. + 45.).rgb;
    fragColor = vec4(col, tex.a * uAlpha);
}