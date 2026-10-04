import zipfile, os, sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

MODS = r"C:\Users\18409\AppData\Roaming\PrismLauncher\instances\GregTech.Odyssey-0.5.6-beta\minecraft\mods"
OUT = r"D:\Program Files (x86)\DeepseekHarness\workspace\_jaraudit"
os.makedirs(OUT, exist_ok=True)

gtocore = os.path.join(MODS, "gtocore-forge-1.20.1-0.5.6-beta.jar")
z = zipfile.ZipFile(gtocore)
nest = [n for n in z.namelist() if n.startswith("META-INF/jarjar/") and "gtceu" in n]
print("nested gtceu jar(s):", nest)
target = os.path.join(OUT, "gtceu.jar")
if nest:
    with open(target, "wb") as f:
        f.write(z.read(nest[0]))
z.close()
print("extracted bytes:", os.path.getsize(target))

pats = {
    b"dev/emi/emi/api/widget/TankWidget": "TankWidget",
    b"dev/emi/emi/api/widget/SlotWidget": "SlotWidget",
    b"dev/emi/emi/api/stack/FluidEmiStack": "FluidEmiStack",
    b"dev/emi/emi/api/widget/WidgetGroup": "WidgetGroup",
    b"dev/emi/emi/api/stack/EmiIngredient": "EmiIngredient",
    b"dev/emi/emi/api/recipe/EmiRecipe": "EmiRecipe",
}
gz = zipfile.ZipFile(target)
names = gz.namelist()
hits = {k: [] for k in pats}
for n in names:
    if not n.endswith(".class"):
        continue
    b = gz.read(n)
    for k in pats:
        if k in b:
            hits[k].append(n)
for k, v in pats.items():
    print(f"\n### {v}: {len(hits[k])} referencing classes")
    for n in hits[k][:14]:
        print("    ", n)

print("\n### GTCEu EMI integration classes (path contains emi):")
for n in names:
    if ".class" in n and "emi" in n.lower() and ("recipeviewer" in n or "integration" in n):
        print("    ", n)
