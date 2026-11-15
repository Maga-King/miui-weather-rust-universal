"""Compile against the real ORT API; exercise preflight and official EOS slicing without weights."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path("C:/Program Files/Java/jdk-17/bin")
ORT = ROOT / ".build/dependencies/onnxruntime-android-1.22.0.jar"
JSON = ROOT / ".build/dependencies/json-20240303.jar"


def test_engine_boundaries():
    assert ORT.is_file(), "Fetch the Android ORT dependency using build.py first"
    with tempfile.TemporaryDirectory(prefix="tibao-engine-", dir=ROOT / ".build") as directory:
        work = Path(directory)
        sources = work / "source"
        sources.mkdir()
        context = sources / "Context.java"
        context.write_text("package android.content; public class Context { public Context getApplicationContext() { return this; } }", "utf-8")
        frontend = sources / "WeatherTibaoFrontend.java"
        frontend.write_text("""package dev.weather.oneplus;
import android.content.Context; import ai.onnxruntime.*;
final class WeatherTibaoFrontend implements AutoCloseable {
  WeatherTibaoFrontend(Context c, OrtEnvironment e, OrtSession b) {}
  static final class Result { long[] phones; float[] bert; String normalizedText; }
  Result prepare(String text) { throw new AssertionError("Frontend must not run in preflight tests"); }
  public void close() {}
} """, "utf-8")
        classes = work / "classes"
        classes.mkdir()
        classpath = ";".join(map(str, (ORT, JSON)))
        subprocess.run([str(JDK / "javac.exe"), "--release", "8", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(classes), str(context), str(frontend), str(ROOT / "port/WeatherTibaoEngine.java"),
                        str(ROOT / "tests/WeatherTibaoEngineCheck.java")], check=True)
        result = subprocess.run([str(JDK / "java.exe"), "-cp", str(classes) + ";" + classpath,
                                 "dev.weather.oneplus.WeatherTibaoEngineCheck"], check=True, capture_output=True, text=True)
        assert "19 checks passed" in result.stdout
        print(result.stdout.strip())


if __name__ == "__main__":
    test_engine_boundaries()
