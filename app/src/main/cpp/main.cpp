#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES

#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <thread>
#include <atomic>
#include <vector>
#include <cstring>
#include <cmath>

#define TAG "LobbyVR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- Swapchain data ----
struct SwapchainData {
    XrSwapchain handle;
    int32_t width;
    int32_t height;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    std::vector<GLuint> framebuffers;
    std::vector<GLuint> depthBuffers;
};

// ---- Global state ----
static JavaVM* gJvm = nullptr;
static jobject gActivity = nullptr;
static std::atomic<bool> gRunning{false};
static std::thread gRenderThread;

// EGL
static EGLDisplay gEglDisplay = EGL_NO_DISPLAY;
static EGLContext gEglContext = EGL_NO_CONTEXT;
static EGLSurface gEglSurface = EGL_NO_SURFACE;
static EGLConfig gEglConfig;

// OpenXR
static XrInstance gInstance = XR_NULL_HANDLE;
static XrSystemId gSystemId = XR_NULL_SYSTEM_ID;
static XrSession gSession = XR_NULL_HANDLE;
static XrSpace gAppSpace = XR_NULL_HANDLE;
static XrSessionState gSessionState = XR_SESSION_STATE_UNKNOWN;
static bool gSessionReady = false;
static std::vector<SwapchainData> gSwapchains;
static std::vector<XrViewConfigurationView> gConfigViews;

// Scene color: dark blue for Lobby (fallback until the panorama texture is uploaded)
static const float CLEAR_R = 0.05f;
static const float CLEAR_G = 0.05f;
static const float CLEAR_B = 0.20f;

// ---- Panorama (equirectangular 360 background) ----
// CPU pixels handed over from Java (BitmapFactory) via nativeSetPanorama; the render
// thread uploads them to a GL texture on the first frame after the flag is set.
static std::vector<uint8_t> gPanoPixels;   // RGBA8, row 0 = top of image
static int gPanoW = 0;
static int gPanoH = 0;
static std::atomic<bool> gPanoDirty{false};
static GLuint gPanoTex = 0;
static GLuint gPanoProgram = 0;
static GLuint gPanoVAO = 0;

static GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static const char* PANO_VS = R"(#version 300 es
// Fullscreen triangle, no vertex buffer needed.
out vec2 vUV;
void main() {
    vec2 p = vec2((gl_VertexID == 1) ? 3.0 : -1.0,
                  (gl_VertexID == 2) ? 3.0 : -1.0);
    vUV = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
)";

static const char* PANO_FS = R"(#version 300 es
precision highp float;
in vec2 vUV;
out vec4 frag;
uniform sampler2D uPano;
uniform vec4 uFovTan;  // (tanLeft, tanRight, tanDown, tanUp); Left/Down are negative
uniform vec4 uQuat;    // head orientation quaternion (x, y, z, w)

vec3 rotate(vec4 q, vec3 v) {
    vec3 t = 2.0 * cross(q.xyz, v);
    return v + q.w * t + cross(q.xyz, t);
}

void main() {
    // View-space ray from per-eye fov tangents (view looks down -Z).
    float x = mix(uFovTan.x, uFovTan.y, vUV.x);
    float y = mix(uFovTan.z, uFovTan.w, vUV.y);
    vec3 dir = normalize(rotate(uQuat, normalize(vec3(x, y, -1.0))));

    const float PI = 3.14159265359;
    // Forward (-Z) maps to the image centre (the portal).
    float u = atan(dir.x, -dir.z) / (2.0 * PI) + 0.5;
    float v = 0.5 - asin(clamp(dir.y, -1.0, 1.0)) / PI;
    frag = texture(uPano, vec2(u, v));
}
)";

static void initPanoProgram() {
    if (gPanoProgram != 0) return;
    GLuint vs = compileShader(GL_VERTEX_SHADER, PANO_VS);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, PANO_FS);
    if (!vs || !fs) return;
    gPanoProgram = glCreateProgram();
    glAttachShader(gPanoProgram, vs);
    glAttachShader(gPanoProgram, fs);
    glLinkProgram(gPanoProgram);
    GLint ok = 0;
    glGetProgramiv(gPanoProgram, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(gPanoProgram, sizeof(log), nullptr, log);
        LOGE("pano program link failed: %s", log);
        glDeleteProgram(gPanoProgram);
        gPanoProgram = 0;
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    glGenVertexArrays(1, &gPanoVAO);
    LOGI("pano program ready");
}

static void uploadPanoIfNeeded() {
    if (!gPanoDirty.load()) return;
    gPanoDirty = false;
    if (gPanoPixels.empty() || gPanoW <= 0 || gPanoH <= 0) return;
    if (gPanoTex == 0) glGenTextures(1, &gPanoTex);
    glBindTexture(GL_TEXTURE_2D, gPanoTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);        // seamless horizontal wrap
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); // clamp poles
    // Upload as sRGB so sampling returns linear; the sRGB swapchain re-encodes on write.
    glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, gPanoW, gPanoH, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, gPanoPixels.data());
    glBindTexture(GL_TEXTURE_2D, 0);
    LOGI("pano texture uploaded %dx%d", gPanoW, gPanoH);
}

// ---- EGL ----
static bool initEGL() {
    gEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gEglDisplay == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); return false; }

    EGLint major, minor;
    if (!eglInitialize(gEglDisplay, &major, &minor)) { LOGE("eglInitialize failed"); return false; }

    EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_NONE
    };
    EGLint numConfigs;
    eglChooseConfig(gEglDisplay, configAttribs, &gEglConfig, 1, &numConfigs);
    if (numConfigs == 0) { LOGE("eglChooseConfig: no configs"); return false; }

    EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    gEglContext = eglCreateContext(gEglDisplay, gEglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (gEglContext == EGL_NO_CONTEXT) { LOGE("eglCreateContext failed"); return false; }

    EGLint pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    gEglSurface = eglCreatePbufferSurface(gEglDisplay, gEglConfig, pbufferAttribs);
    eglMakeCurrent(gEglDisplay, gEglSurface, gEglSurface, gEglContext);

    LOGI("EGL initialized: %d.%d", major, minor);
    return true;
}

static void shutdownEGL() {
    if (gEglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(gEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (gEglContext != EGL_NO_CONTEXT) eglDestroyContext(gEglDisplay, gEglContext);
        if (gEglSurface != EGL_NO_SURFACE) eglDestroySurface(gEglDisplay, gEglSurface);
        eglTerminate(gEglDisplay);
    }
    gEglDisplay = EGL_NO_DISPLAY;
    gEglContext = EGL_NO_CONTEXT;
    gEglSurface = EGL_NO_SURFACE;
}

// ---- OpenXR init ----
static bool initOpenXR() {
    // Initialize loader (Android requires this)
    PFN_xrInitializeLoaderKHR initLoader = nullptr;
    xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction*)&initLoader);
    if (initLoader) {
        XrLoaderInitInfoAndroidKHR loaderInfo = {XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loaderInfo.applicationVM = gJvm;
        loaderInfo.applicationContext = gActivity;
        initLoader((XrLoaderInitInfoBaseHeaderKHR*)&loaderInfo);
    }

    // Create instance
    const char* extensions[] = {
        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
    };

    XrInstanceCreateInfoAndroidKHR androidInfo = {XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM = gJvm;
    androidInfo.applicationActivity = gActivity;

    XrInstanceCreateInfo createInfo = {XR_TYPE_INSTANCE_CREATE_INFO};
    createInfo.next = &androidInfo;
    createInfo.enabledExtensionCount = 2;
    createInfo.enabledExtensionNames = extensions;
    strcpy(createInfo.applicationInfo.applicationName, "Lobby");
    createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

    if (XR_FAILED(xrCreateInstance(&createInfo, &gInstance))) {
        LOGE("xrCreateInstance failed");
        return false;
    }
    LOGI("OpenXR instance created");

    // Get system
    XrSystemGetInfo systemInfo = {XR_TYPE_SYSTEM_GET_INFO};
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    if (XR_FAILED(xrGetSystem(gInstance, &systemInfo, &gSystemId))) {
        LOGE("xrGetSystem failed");
        return false;
    }

    // View configuration
    uint32_t viewCount = 0;
    xrEnumerateViewConfigurationViews(gInstance, gSystemId,
        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &viewCount, nullptr);
    gConfigViews.resize(viewCount, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
    xrEnumerateViewConfigurationViews(gInstance, gSystemId,
        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, viewCount, &viewCount, gConfigViews.data());

    // Graphics requirements (must be called before creating session)
    PFN_xrGetOpenGLESGraphicsRequirementsKHR getGLReq = nullptr;
    xrGetInstanceProcAddr(gInstance, "xrGetOpenGLESGraphicsRequirementsKHR", (PFN_xrVoidFunction*)&getGLReq);
    if (getGLReq) {
        XrGraphicsRequirementsOpenGLESKHR req = {XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        getGLReq(gInstance, gSystemId, &req);
    }

    // Create session
    XrGraphicsBindingOpenGLESAndroidKHR gfxBinding = {XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    gfxBinding.display = gEglDisplay;
    gfxBinding.config = gEglConfig;
    gfxBinding.context = gEglContext;

    XrSessionCreateInfo sessionInfo = {XR_TYPE_SESSION_CREATE_INFO};
    sessionInfo.next = &gfxBinding;
    sessionInfo.systemId = gSystemId;
    if (XR_FAILED(xrCreateSession(gInstance, &sessionInfo, &gSession))) {
        LOGE("xrCreateSession failed");
        return false;
    }
    LOGI("OpenXR session created");

    // Reference space
    XrReferenceSpaceCreateInfo spaceInfo = {XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    xrCreateReferenceSpace(gSession, &spaceInfo, &gAppSpace);

    // Create swapchains
    gSwapchains.resize(viewCount);
    for (uint32_t i = 0; i < viewCount; i++) {
        XrSwapchainCreateInfo scInfo = {XR_TYPE_SWAPCHAIN_CREATE_INFO};
        scInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        scInfo.format = GL_SRGB8_ALPHA8;
        scInfo.sampleCount = 1;
        scInfo.width = gConfigViews[i].recommendedImageRectWidth;
        scInfo.height = gConfigViews[i].recommendedImageRectHeight;
        scInfo.faceCount = 1;
        scInfo.arraySize = 1;
        scInfo.mipCount = 1;

        xrCreateSwapchain(gSession, &scInfo, &gSwapchains[i].handle);
        gSwapchains[i].width = scInfo.width;
        gSwapchains[i].height = scInfo.height;

        // Enumerate images
        uint32_t imgCount = 0;
        xrEnumerateSwapchainImages(gSwapchains[i].handle, 0, &imgCount, nullptr);
        gSwapchains[i].images.resize(imgCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        xrEnumerateSwapchainImages(gSwapchains[i].handle, imgCount, &imgCount,
            (XrSwapchainImageBaseHeader*)gSwapchains[i].images.data());

        // Create framebuffers + depth
        gSwapchains[i].framebuffers.resize(imgCount);
        gSwapchains[i].depthBuffers.resize(imgCount);
        glGenFramebuffers(imgCount, gSwapchains[i].framebuffers.data());
        glGenRenderbuffers(imgCount, gSwapchains[i].depthBuffers.data());

        for (uint32_t j = 0; j < imgCount; j++) {
            glBindFramebuffer(GL_FRAMEBUFFER, gSwapchains[i].framebuffers[j]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                gSwapchains[i].images[j].image, 0);

            glBindRenderbuffer(GL_RENDERBUFFER, gSwapchains[i].depthBuffers[j]);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, scInfo.width, scInfo.height);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER,
                gSwapchains[i].depthBuffers[j]);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    LOGI("OpenXR swapchains created (%d views)", viewCount);
    return true;
}

// ---- Events ----
static void handleEvents() {
    XrEventDataBuffer event = {XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(gInstance, &event) == XR_SUCCESS) {
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto* s = (XrEventDataSessionStateChanged*)&event;
            gSessionState = s->state;
            LOGI("Session state: %d", gSessionState);

            if (gSessionState == XR_SESSION_STATE_READY) {
                XrSessionBeginInfo beginInfo = {XR_TYPE_SESSION_BEGIN_INFO};
                beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                xrBeginSession(gSession, &beginInfo);
                gSessionReady = true;
            } else if (gSessionState == XR_SESSION_STATE_STOPPING) {
                xrEndSession(gSession);
                gSessionReady = false;
            } else if (gSessionState == XR_SESSION_STATE_EXITING ||
                       gSessionState == XR_SESSION_STATE_LOSS_PENDING) {
                gRunning = false;
            }
        }
        event = {XR_TYPE_EVENT_DATA_BUFFER};
    }
}

// ---- Render ----
static void renderFrame() {
    if (!gSessionReady) return;

    uploadPanoIfNeeded();  // GL context is current on this thread

    XrFrameWaitInfo waitInfo = {XR_TYPE_FRAME_WAIT_INFO};
    XrFrameState frameState = {XR_TYPE_FRAME_STATE};
    if (XR_FAILED(xrWaitFrame(gSession, &waitInfo, &frameState))) return;

    XrFrameBeginInfo beginInfo = {XR_TYPE_FRAME_BEGIN_INFO};
    if (XR_FAILED(xrBeginFrame(gSession, &beginInfo))) return;

    std::vector<XrCompositionLayerBaseHeader*> layers;
    XrCompositionLayerProjection layer = {XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    std::vector<XrCompositionLayerProjectionView> projViews;

    if (frameState.shouldRender) {
        uint32_t viewCount = gConfigViews.size();
        std::vector<XrView> views(viewCount, {XR_TYPE_VIEW});

        XrViewLocateInfo locateInfo = {XR_TYPE_VIEW_LOCATE_INFO};
        locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        locateInfo.displayTime = frameState.predictedDisplayTime;
        locateInfo.space = gAppSpace;

        XrViewState viewState = {XR_TYPE_VIEW_STATE};
        xrLocateViews(gSession, &locateInfo, &viewState, viewCount, &viewCount, views.data());

        projViews.resize(viewCount);
        for (uint32_t i = 0; i < viewCount; i++) {
            XrSwapchainImageAcquireInfo acqInfo = {XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
            uint32_t imgIdx;
            xrAcquireSwapchainImage(gSwapchains[i].handle, &acqInfo, &imgIdx);

            XrSwapchainImageWaitInfo swWait = {XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
            swWait.timeout = XR_INFINITE_DURATION;
            xrWaitSwapchainImage(gSwapchains[i].handle, &swWait);

            glBindFramebuffer(GL_FRAMEBUFFER, gSwapchains[i].framebuffers[imgIdx]);
            glViewport(0, 0, gSwapchains[i].width, gSwapchains[i].height);
            glClearColor(CLEAR_R, CLEAR_G, CLEAR_B, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            // Draw the 360 panorama as the background, using this eye's fov + head orientation.
            if (gPanoTex != 0 && gPanoProgram != 0) {
                glDisable(GL_DEPTH_TEST);
                glDepthMask(GL_FALSE);
                glUseProgram(gPanoProgram);
                const XrFovf& fov = views[i].fov;
                const XrQuaternionf& q = views[i].pose.orientation;
                glUniform4f(glGetUniformLocation(gPanoProgram, "uFovTan"),
                            tanf(fov.angleLeft), tanf(fov.angleRight),
                            tanf(fov.angleDown), tanf(fov.angleUp));
                glUniform4f(glGetUniformLocation(gPanoProgram, "uQuat"), q.x, q.y, q.z, q.w);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, gPanoTex);
                glUniform1i(glGetUniformLocation(gPanoProgram, "uPano"), 0);
                glBindVertexArray(gPanoVAO);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glBindVertexArray(0);
                glDepthMask(GL_TRUE);
            }

            XrSwapchainImageReleaseInfo relInfo = {XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            xrReleaseSwapchainImage(gSwapchains[i].handle, &relInfo);

            projViews[i] = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
            projViews[i].pose = views[i].pose;
            projViews[i].fov = views[i].fov;
            projViews[i].subImage.swapchain = gSwapchains[i].handle;
            projViews[i].subImage.imageRect.offset = {0, 0};
            projViews[i].subImage.imageRect.extent = {gSwapchains[i].width, gSwapchains[i].height};
        }

        layer.space = gAppSpace;
        layer.viewCount = viewCount;
        layer.views = projViews.data();
        layers.push_back((XrCompositionLayerBaseHeader*)&layer);
    }

    XrFrameEndInfo endInfo = {XR_TYPE_FRAME_END_INFO};
    endInfo.displayTime = frameState.predictedDisplayTime;
    endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    endInfo.layerCount = layers.size();
    endInfo.layers = layers.data();
    xrEndFrame(gSession, &endInfo);
}

// ---- Cleanup ----
static void shutdownOpenXR() {
    if (gPanoTex)     { glDeleteTextures(1, &gPanoTex); gPanoTex = 0; }
    if (gPanoVAO)     { glDeleteVertexArrays(1, &gPanoVAO); gPanoVAO = 0; }
    if (gPanoProgram) { glDeleteProgram(gPanoProgram); gPanoProgram = 0; }
    for (auto& sc : gSwapchains) {
        if (!sc.framebuffers.empty()) glDeleteFramebuffers(sc.framebuffers.size(), sc.framebuffers.data());
        if (!sc.depthBuffers.empty()) glDeleteRenderbuffers(sc.depthBuffers.size(), sc.depthBuffers.data());
        if (sc.handle != XR_NULL_HANDLE) xrDestroySwapchain(sc.handle);
    }
    gSwapchains.clear();
    gConfigViews.clear();
    if (gAppSpace != XR_NULL_HANDLE) { xrDestroySpace(gAppSpace); gAppSpace = XR_NULL_HANDLE; }
    if (gSession != XR_NULL_HANDLE) { xrDestroySession(gSession); gSession = XR_NULL_HANDLE; }
    if (gInstance != XR_NULL_HANDLE) { xrDestroyInstance(gInstance); gInstance = XR_NULL_HANDLE; }
    gSessionReady = false;
    gSessionState = XR_SESSION_STATE_UNKNOWN;
}

// ---- Render thread ----
static void renderThreadFunc() {
    if (!initEGL()) { LOGE("EGL init failed"); return; }
    if (!initOpenXR()) { LOGE("OpenXR init failed"); shutdownEGL(); return; }

    initPanoProgram();

    LOGI("VR render loop starting");
    while (gRunning) {
        handleEvents();
        renderFrame();
    }

    shutdownOpenXR();
    shutdownEGL();
    LOGI("VR render loop ended");
}

// ---- JNI ----
extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_quest_lobby_MainActivity_nativeSetPanorama(JNIEnv* env, jobject, jint w, jint h, jobject buffer) {
    void* addr = env->GetDirectBufferAddress(buffer);
    if (!addr || w <= 0 || h <= 0) {
        LOGE("nativeSetPanorama: bad input (addr=%p, %dx%d)", addr, w, h);
        return;
    }
    size_t bytes = (size_t)w * (size_t)h * 4;
    uint8_t* p = (uint8_t*)addr;
    gPanoPixels.assign(p, p + bytes);
    gPanoW = w;
    gPanoH = h;
    gPanoDirty = true;   // render thread uploads it on the next frame
    LOGI("nativeSetPanorama received %dx%d", w, h);
}

JNIEXPORT void JNICALL
Java_com_quest_lobby_MainActivity_nativeCreate(JNIEnv* env, jobject activity) {
    if (gRunning) return;
    gActivity = env->NewGlobalRef(activity);
    gRunning = true;
    gRenderThread = std::thread(renderThreadFunc);
}

JNIEXPORT void JNICALL
Java_com_quest_lobby_MainActivity_nativeDestroy(JNIEnv* env, jobject activity) {
    gRunning = false;
    if (gRenderThread.joinable()) gRenderThread.join();
    if (gActivity) {
        env->DeleteGlobalRef(gActivity);
        gActivity = nullptr;
    }
}

} // extern "C"
