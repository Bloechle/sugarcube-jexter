# site.sh <web dir> : copy the Jexter web app and point its CDN URLs at /vendor
rm -rf /tmp/jxb/site && cp -r "$1" /tmp/jxb/site && cp -r /tmp/jxb/vendor /tmp/jxb/site/vendor
cd /tmp/jxb/site && sed -i \
 -e 's#https://cdn.jsdelivr.net/gh/Bloechle/qry-js@1.3.0/#/vendor/#g' \
 -e 's#https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.20.1/cdn/#/vendor/sl/package/cdn/#g' \
 -e 's#https://cdn.jsdelivr.net/npm/lucide@1.17.0/dist/umd/lucide.min.js#/vendor/lucide.min.js#g' \
 -e 's#<link[^>]*fontsource[^>]*>##g' index.html
grep -c jsdelivr index.html
