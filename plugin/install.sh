#!/bin/sh
# dsh-pet-bridge 一键安装：把自己拷进 DSH profile 并登记到 bundles
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=${DSH_HOME:-$HOME/.dsh}
PROFILE=${DSH_PROFILE:-web}
DEST="$ROOT/profiles/$PROFILE/node_modules"
mkdir -p "$DEST"
rm -rf "$DEST/dsh-pet-bridge"
cp -a "$HERE" "$DEST/dsh-pet-bridge"
node -e '
const fs=require("fs");
const p=process.argv[1];
const d=JSON.parse(fs.readFileSync(p,"utf8"));
d.dsh=d.dsh||{};d.dsh.profile=d.dsh.profile||{};
let b=d.dsh.profile.bundles||[];
b=b.filter(x=>x!=="dsh-pet");
if(!b.includes("dsh-pet-bridge"))b.push("dsh-pet-bridge");
d.dsh.profile.bundles=b;
fs.writeFileSync(p,JSON.stringify(d,null,2));
console.log("bundles:",b.join(", "));
' "$ROOT/profiles/$PROFILE/package.json"
echo "dsh-pet-bridge 安装完成，重启 DSHA 生效"
