#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

python -m mkdocs build --strict -f mkdocs-zh.yml
python -m mkdocs build --strict -f mkdocs-en.yml
python -m mkdocs build --strict -f mkdocs-tw.yml
python -m mkdocs build --strict -f mkdocs-ja.yml

rm -rf _site
mkdir -p _site/zh _site/en _site/tw _site/ja
cp -r site-zh/* _site/zh/
cp -r site-en/* _site/en/
cp -r site-tw/* _site/tw/
cp -r site-ja/* _site/ja/

cat > _site/index.html << 'EOF'
<!DOCTYPE html>
<html>
<head><meta http-equiv="refresh" content="0; url=zh/"></head>
<body><a href="zh/">前往中文文档</a></body>
</html>
EOF

echo "Done: docs-site/_site"
