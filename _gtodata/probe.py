import zipfile, json, os, glob, re, sys, collections
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

MODS = r"C:\Users\18409\AppData\Roaming\PrismLauncher\instances\GregTech.Odyssey-0.5.6-beta\minecraft\mods"
WS = r"D:\Program Files (x86)\DeepseekHarness\workspace"

print("=" * 20, "PART 1: how real 1.20.1 Forge mods declare mixins", "=" * 20)
hits = []
for p in sorted(glob.glob(os.path.join(MODS, "*.jar"))):
    try:
        z = zipfile.ZipFile(p)
    except Exception:
        continue
    names = z.namelist()
    mix = [n for n in names if n.endswith(".mixins.json")]
    if not mix:
        z.close()
        continue
    man = toml = ""
    try:
        man = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    except Exception:
        pass
    try:
        toml = z.read("META-INF/mods.toml").decode("utf-8", "replace")
    except Exception:
        pass
    refs = [n for n in names if "refmap" in n.lower()]
    hits.append(dict(jar=os.path.basename(p), mix=mix,
                     man=re.findall(r"MixinConfigs:[^\r\n]*", man),
                     hasmix="[[mixins]]" in toml,
                     mixblock=(re.search(r"\[\[mixins\]\][\s\S]{0,300}", toml).group(0) if "[[mixins]]" in toml else ""),
                     refmaps=refs, z=z, toml=toml))
    z.close()

print("jars containing *.mixins.json:", len(hits))
for h in hits[:14]:
    print("-", h["jar"])
    print("    configs   :", ", ".join(h["mix"][:4]))
    print("    manifest  :", h["man"] or "(no MixinConfigs in MANIFEST.MF)")
    print("    toml mixins:", h["hasmix"], "| refmap entries in jar:", h["refmaps"][:3])

for h in hits[:2]:
    z = zipfile.ZipFile(os.path.join(MODS, h["jar"]))
    print("\n--- sample mixin config from", h["jar"], ":", h["mix"][0])
    print(z.read(h["mix"][0]).decode("utf-8", "replace")[:700])
    if h["mixblock"]:
        print("--- [[mixins]] block from its mods.toml:")
        print(h["mixblock"])
    z.close()

print()
print("=" * 20, "PART 2: GT lang key structure (fluid name source)", "=" * 20)
for mod in ("gtceu", "gtocore"):
    for lang in ("zh_cn", "en_us"):
        fp = os.path.join(WS, "_gtodata", "assets", mod, "lang", lang + ".json")
        if not os.path.exists(fp):
            print(mod, lang, "MISSING", fp)
            continue
        d = json.load(open(fp, encoding="utf-8"))
        pref = collections.Counter(k.split(".")[0] for k in d)
        print(f"{mod}/{lang}: {len(d)} keys; top prefixes: {pref.most_common(8)}")
        for pat in ("fluid", "block"):
            ks = [k for k in d if k.startswith(pat + ".") and "gtceu" in k][:5]
            if ks:
                print("   sample", pat, "keys:", [(k, d[k]) for k in ks])
