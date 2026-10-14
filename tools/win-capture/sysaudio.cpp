// Captures what Windows plays on the default output device (WASAPI loopback) and writes it to stdout as 16 kHz mono
// float32 little-endian, the format the pipeline's Segmenter takes (the same as tools/mac-capture's sysaudio).
//   sysaudio.exe [seconds]     stops after <seconds> (default: until the reader goes away or the process is ended)
// Loopback delivers nothing while nothing plays: the gaps are written as silence, so that the stream keeps real time
// and a sentence that ends with the sound still ends. A change of the default device (headphones in or out) is
// followed. Every 5 s stderr says how much sound arrived (packets, silent ones, peak), to tell "nothing plays on this
// device" from a fault. Build (MSVC, static runtime): cl /nologo /O2 /EHsc /MT sysaudio.cpp ole32.lib
#define WIN32_LEAN_AND_MEAN
#define _USE_MATH_DEFINES
#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <functiondiscoverykeys_devpkey.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

static const double OUT_RATE = 16000;
static HANDLE out;

static void fail(const char* what, HRESULT hr) {
    fprintf(stderr, "sysaudio: %s failed (0x%08lx)\n", what, (unsigned long)hr);
    fflush(stderr);
    ExitProcess(1);
}

static void write(const std::vector<float>& v) {
    if (v.empty()) return;
    DWORD n = 0, size = (DWORD)(v.size() * sizeof(float));
    if (!WriteFile(out, v.data(), size, &n, nullptr) || n != size) ExitProcess(0); // reader gone
}

/** Mono input at [rate] → 16 kHz: a windowed-sinc low-pass below 8 kHz, then linear interpolation between samples. */
struct Resampler {
    double step, pos = 0; // pos: the next output sample, in filtered input samples; -1 is the previous block's last
    float prev = 0;
    std::vector<float> taps, hist, filtered;
    size_t at = 0; // hist is a ring of taps.size() samples, at = the oldest
    explicit Resampler(double rate) : step(rate / OUT_RATE) {
        const int n = 63;
        double cut = 7200 / rate, sum = 0;
        for (int i = 0; i < n; i++) {
            double x = i - (n - 1) / 2.0;
            double s = x == 0 ? 2 * cut : sin(2 * M_PI * cut * x) / (M_PI * x);
            double w = 0.42 - 0.5 * cos(2 * M_PI * i / (n - 1)) + 0.08 * cos(4 * M_PI * i / (n - 1)); // Blackman
            taps.push_back((float)(s * w)); sum += s * w;
        }
        for (auto& t : taps) t = (float)(t / sum);
        hist.assign(n, 0.f);
    }
    void push(const float* in, size_t count, std::vector<float>& result) {
        size_t n = taps.size();
        filtered.clear();
        for (size_t i = 0; i < count; i++) {
            hist[at] = in[i]; at = (at + 1) % n;
            float acc = 0;
            for (size_t k = 0; k < n; k++) acc += taps[k] * hist[(at + k) % n];
            filtered.push_back(acc);
        }
        long size = (long)filtered.size();
        for (;;) {
            long i = (long)floor(pos);
            if (i + 1 >= size) break; // the next sample is in the next block
            float a = i < 0 ? prev : filtered[i], b = filtered[i + 1];
            result.push_back((float)(a + (b - a) * (pos - i)));
            pos += step;
        }
        pos -= size;
        if (size) prev = filtered.back();
    }
};

/** One loopback stream on the current default device; false when the device went away (start again). */
static bool capture(double seconds, ULONGLONG started) {
    IMMDeviceEnumerator* devices = nullptr;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator),
                                  (void**)&devices);
    if (FAILED(hr)) fail("MMDeviceEnumerator", hr);
    IMMDevice* device = nullptr;
    hr = devices->GetDefaultAudioEndpoint(eRender, eConsole, &device);
    if (FAILED(hr)) fail("GetDefaultAudioEndpoint (no output device?)", hr);
    IAudioClient* client = nullptr;
    hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void**)&client);
    if (FAILED(hr)) fail("IAudioClient", hr);
    WAVEFORMATEX* format = nullptr;
    hr = client->GetMixFormat(&format);
    if (FAILED(hr)) fail("GetMixFormat", hr);
    bool isFloat = format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT ||
        (format->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
         ((WAVEFORMATEXTENSIBLE*)format)->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
    int channels = format->nChannels, bits = format->wBitsPerSample;
    if (!(isFloat && bits == 32) && !(!isFloat && (bits == 16 || bits == 32))) fail("mix format", E_FAIL);
    hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 2000000 /* 200 ms */, 0, format,
                            nullptr);
    if (FAILED(hr)) fail("IAudioClient::Initialize", hr);
    IAudioCaptureClient* cap = nullptr;
    hr = client->GetService(__uuidof(IAudioCaptureClient), (void**)&cap);
    if (FAILED(hr)) fail("IAudioCaptureClient", hr);
    hr = client->Start();
    if (FAILED(hr)) fail("IAudioClient::Start", hr);
    double rate = format->nSamplesPerSec;
    // the device's name as Windows' sound settings show it (UTF-8)
    char name[256] = "?";
    IPropertyStore* props = nullptr;
    if (SUCCEEDED(device->OpenPropertyStore(STGM_READ, &props))) {
        PROPVARIANT v; PropVariantInit(&v);
        if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &v)) && v.vt == VT_LPWSTR)
            WideCharToMultiByte(CP_UTF8, 0, v.pwszVal, -1, name, sizeof name, nullptr, nullptr);
        PropVariantClear(&v); props->Release();
    }
    fprintf(stderr, "sysaudio: capturing \"%s\" %d Hz x %d (%s%d) -> 16 kHz mono\n", name, (int)rate, channels,
            isFloat ? "float" : "int", bits);
    fflush(stderr);

    Resampler rs(rate);
    std::vector<float> mono, result;
    // loopback leaves out the time nothing plays: after 100 ms without a packet, that time is written as silence
    ULONGLONG lastData = GetTickCount64();
    double silent = 0; // input frames of silence written since lastData
    bool lost = false;
    // the last 5 s: packets, silent packets, frames, peak
    ULONGLONG statAt = GetTickCount64();
    unsigned packets = 0, silentPackets = 0; double frames5 = 0; float peak = 0;
    for (;;) {
        Sleep(10);
        result.clear();
        UINT32 packet = 0;
        while (SUCCEEDED(hr = cap->GetNextPacketSize(&packet)) && packet > 0) {
            BYTE* data = nullptr; UINT32 frames = 0; DWORD flags = 0;
            hr = cap->GetBuffer(&data, &frames, &flags, nullptr, nullptr);
            if (FAILED(hr)) break;
            mono.assign(frames, 0.f);
            packets++; frames5 += frames;
            if (flags & AUDCLNT_BUFFERFLAGS_SILENT) silentPackets++;
            else {
                for (UINT32 f = 0; f < frames; f++) {
                    float s = 0;
                    for (int c = 0; c < channels; c++) {
                        size_t i = (size_t)f * channels + c;
                        if (isFloat) s += ((float*)data)[i];
                        else if (bits == 16) s += ((short*)data)[i] / 32768.f;
                        else s += ((int*)data)[i] / 2147483648.f;
                    }
                    mono[f] = s / channels;
                    if (fabsf(mono[f]) > peak) peak = fabsf(mono[f]);
                }
            }
            cap->ReleaseBuffer(frames);
            rs.push(mono.data(), mono.size(), result);
        }
        if (FAILED(hr)) { lost = true; break; } // AUDCLNT_E_DEVICE_INVALIDATED and the like: start over
        ULONGLONG now = GetTickCount64();
        if (!result.empty()) { lastData = now; silent = 0; }
        else if (now - lastData > 100) {
            double due = (now - lastData - 100) / 1000.0 * rate;
            if (due > silent) {
                size_t n = (size_t)(due - silent);
                mono.assign(n, 0.f);
                rs.push(mono.data(), n, result);
                silent += n;
            }
        }
        write(result);
        if (now - statAt >= 5000) {
            fprintf(stderr, "sysaudio: 5 s: %u packets (%u silent), %.1f s of sound, peak %.4f\n", packets, silentPackets,
                    frames5 / rate, peak);
            fflush(stderr);
            statAt = now; packets = silentPackets = 0; frames5 = 0; peak = 0;
        }
        if (seconds > 0 && (GetTickCount64() - started) / 1000.0 >= seconds) break;
    }
    client->Stop();
    cap->Release(); client->Release(); CoTaskMemFree(format); device->Release(); devices->Release();
    if (lost) { fprintf(stderr, "sysaudio: output device changed, following the new default\n"); fflush(stderr); }
    return !lost;
}

int main(int argc, char** argv) {
    double seconds = argc > 1 ? atof(argv[1]) : 0;
    out = GetStdHandle(STD_OUTPUT_HANDLE);
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr)) fail("CoInitializeEx", hr);
    ULONGLONG started = GetTickCount64();
    while (!capture(seconds, started)) Sleep(200);
    CoUninitialize();
    return 0;
}
