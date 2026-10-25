# Animation Performance Audit

Date: 2026-10-21

`port/host.c` now skips native-to-Java surface requests when position rounds to
the same pixel and size/window identity are unchanged. `port/MajesticSurface.java`
avoids redundant layout, visibility, and log updates, and keeps at most one
Choreographer callback or PixelCopy request outstanding. PixelCopy remains
full-size and resumes sampling on the next display frame after each completion.

The PixelCopy-to-AHardwareBuffer path still allocates a buffer per submitted
frame. It was not pooled: the renderer retains frames asynchronously, and safe
reuse would require verified ownership and fence behavior. This audit changes
neither the packaged native-resolution/render-pacing behavior nor MAML quality,
glass effects, or the 120 Hz display request policy. Six exact legacy MiSans
static-weight asset names now resolve to the bundled variable font as a
compatibility fallback.

Evidence and limits: source inspection identified repeated unchanged geometry
requests and callback rescheduling while a copy was pending. On the connected
device, the inspected `WeatherMGL`/`WeatherHost` log window had no matching
entries, so no callback rate, FPS, or device-specific performance improvement
was measured. NDK C syntax checking, Java compilation against Android 36, and
`git diff --check` passed; a full APK build and on-device before/after benchmark
were not run.

This task edited `port/host.c` and `port/MajesticSurface.java`. Existing
workspace changes in those files, and in `port/WeatherRenderPreferences.java`,
were preserved; `WeatherRenderPreferences.java` received no edits in this task.
