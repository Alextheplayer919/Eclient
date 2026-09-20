#!/bin/bash

# Değişkenler (Token burada yok!)
USERNAME="adanainv3-creator"
REPO="github.com/adanainv3-creator/OxClient.git"
BRANCH="main"

# Kontrol: Eğer değişken boşsa kullanıcıyı uyar
if [ -z "$MY_GITHUB_TOKEN" ]; then
    echo "Hata: MY_GITHUB_TOKEN tanımlı değil!"
    echo "Lütfen: export MY_GITHUB_TOKEN=senin_tokenin komutunu çalıştır."
    exit 1
fi

echo "Dosyalar ekleniyor ve commit yapılıyor..."
git add .
git commit -m "Force push via env variable: $(date +'%Y-%m-%d %H:%M:%S')"

echo "ZORLA push yapılıyor..."
# Token'ı değişken üzerinden URL'ye gömüyoruz
git push -f "https://$USERNAME:$MY_GITHUB_TOKEN@$REPO" $BRANCH

echo "İşlem Tamam!"
