"""Windows build of the launcher for Fire OS 7 (API 28). Usage: python build_win.py [profile]  (profile = folder in profiles/ whose config.xml replaces res/values/config.xml)"""
import os, shutil, subprocess, sys, zipfile, glob

HERE = os.path.dirname(os.path.abspath(__file__))
TC = os.path.join(HERE, "..", "toolchain")
JDK = os.path.join(TC, "jdk", "jdk-17.0.20.1+1")
SDK = os.path.join(TC, "sdk")
BT = os.path.join(SDK, "build-tools", "34.0.0")
JAR = os.path.join(SDK, "platforms", "android-28", "android.jar")
B = os.path.join(HERE, "build")
env = dict(os.environ, JAVA_HOME=JDK, PATH=os.path.join(JDK, "bin") + os.pathsep + os.environ["PATH"])


def run(*a):
    r = subprocess.run(list(a), cwd=HERE, env=env, capture_output=True, text=True)
    if r.returncode:
        print(r.stdout, r.stderr)
        sys.exit("FAILED: " + " ".join(a)[:120])
    return r.stdout


shutil.rmtree(B, ignore_errors=True)
for d in ("gen", "classes", "dex"):
    os.makedirs(os.path.join(B, d))
PROFILE = sys.argv[1] if len(sys.argv) > 1 else ""
RES = "res"
if PROFILE:
    RES = os.path.join("build", "res_" + PROFILE)
    shutil.copytree("res", RES)
    shutil.copy(os.path.join("profiles", PROFILE, "config.xml"), os.path.join(RES, "values", "config.xml"))
ASSETS = "assets"
if PROFILE and os.path.isdir(os.path.join("profiles", PROFILE, "assets")):   # profile-only files, e.g. brand logos
    ASSETS = os.path.join("build", "assets_" + PROFILE)
    shutil.copytree("assets", ASSETS)
    shutil.copytree(os.path.join("profiles", PROFILE, "assets"), ASSETS, dirs_exist_ok=True)
run(os.path.join(BT, "aapt2.exe"), "compile", "--dir", RES, "-o", os.path.join(B, "res.zip"))
run(os.path.join(BT, "aapt2.exe"), "link", "-o", os.path.join(B, "base.apk"), "-I", JAR, "--manifest", "AndroidManifest.xml",
    "--java", os.path.join(B, "gen"), "-A", ASSETS, "--min-sdk-version", "28", "--target-sdk-version", "28", os.path.join(B, "res.zip"))
srcs = glob.glob("src/**/*.java", root_dir=HERE, recursive=True) + [os.path.relpath(p, HERE) for p in glob.glob(os.path.join(B, "gen", "**", "*.java"), recursive=True)]
run(os.path.join(JDK, "bin", "javac.exe"), "-encoding", "UTF-8", "-nowarn", "-Xlint:-options", "--release", "11", "-classpath", JAR, "-d", os.path.join(B, "classes"), *srcs)
classes = [p for p in glob.glob(os.path.join(B, "classes", "**", "*.class"), recursive=True)]
run(os.path.join(BT, "d8.bat"), "--min-api", "28", "--lib", JAR, "--output", os.path.join(B, "dex"), *classes)
shutil.copy(os.path.join(B, "base.apk"), os.path.join(B, "unsigned.apk"))
with zipfile.ZipFile(os.path.join(B, "unsigned.apk"), "a") as z:
    z.write(os.path.join(B, "dex", "classes.dex"), "classes.dex")
run(os.path.join(BT, "zipalign.exe"), "-f", "4", os.path.join(B, "unsigned.apk"), os.path.join(B, "aligned.apk"))
ks = os.path.join(HERE, "debug.keystore")
if not os.path.exists(ks):
    run(os.path.join(JDK, "bin", "keytool.exe"), "-genkeypair", "-keystore", ks, "-storepass", "android", "-keypass", "android", "-alias", "tvhome",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=tvhome")
run(os.path.join(BT, "apksigner.bat"), "sign", "--ks", ks, "--ks-pass", "pass:android", "--out", os.path.join(B, "tvhome.apk"), os.path.join(B, "aligned.apk"))
print("built", os.path.join(B, "tvhome.apk"), os.path.getsize(os.path.join(B, "tvhome.apk")), "bytes")
os.makedirs("out", exist_ok=True)
shutil.copy(os.path.join(B, "tvhome.apk"), os.path.join("out", "tvhome-" + PROFILE + ".apk" if PROFILE else "tvhome.apk"))
print("copied to launcher/out/")
