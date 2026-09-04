#!/data/data/com.termux/files/usr/bin/bash
set -e

ASSETS="app/src/main/assets"
DRAWABLE="app/src/main/res/drawable"

mkdir -p "$DRAWABLE"

declare -A ICON_MAP=(
  ["96px-Netherite_Axe_JE2_BE2.png"]="item_netherite_axe.png"
  ["96px-Netherite_Boots_(item)_JE2_BE2.png"]="item_netherite_boots.png"
  ["96px-Netherite_Leggings_(item)_JE2_BE1.png"]="item_netherite_leggings.png"
  ["96px-Netherite_Pickaxe_JE3_BE2.png"]="item_netherite_pickaxe.png"
  ["96px-Netherite_Sword_JE2_BE2.png"]="item_netherite_sword.png"
  ["96px-Potion_of_Strength_BE3.png"]="item_potion_of_strength.png"
  ["Netherite_Chestplate_(item)_JE2_BE1.png"]="item_netherite_chestplate.png"
  ["Netherite_Helmet_(item)_JE2_BE1.png"]="item_netherite_helmet.png"
)

for src in "${!ICON_MAP[@]}"; do
  dst="${ICON_MAP[$src]}"
  if [ -f "$ASSETS/$src" ]; then
    mv -v "$ASSETS/$src" "$DRAWABLE/$dst"
  else
    echo "SKIP (bulunamadi): $src"
  fi
done

echo "Bitti. $DRAWABLE altindaki dosyalar:"
ls -1 "$DRAWABLE" | grep '^item_'

