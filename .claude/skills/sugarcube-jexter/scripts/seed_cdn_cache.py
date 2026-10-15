#!/usr/bin/env python3
"""Seed WebApp's /cdn/ proxy disk cache (~/.jexter/cdn) from network-allowed mirrors,
so Prism/PDFInspector render fully in the sandbox where cdn.jsdelivr.net is blocked.
Layout: <cacheRoot>/<rel> + <rel>.ct (content-type sidecar); text assets stored
POST-rewrite (https://cdn.jsdelivr.net/ -> /cdn/), matching WebApp.rewriteCdn."""
import os, shutil, re, glob, subprocess, sys

CDN = os.path.expanduser('~/.jexter/cdn')
M = '/tmp/cdnmirror'
QRY_TAG = sys.argv[1] if len(sys.argv) > 1 else '1.3.0'
TEXT = ('.css', '.js', '.mjs', '.map', '+esm')

def sh(cmd): subprocess.run(cmd, shell=True, check=True)
def rw(s): return s.replace('https://cdn.jsdelivr.net/', '/cdn/').replace('//cdn.jsdelivr.net/', '/cdn/')
def seed(rel, src, ct=None):
    dst = os.path.join(CDN, rel); os.makedirs(os.path.dirname(dst), exist_ok=True)
    if rel.endswith(TEXT):
        open(dst, 'w', encoding='utf-8', newline='\n').write(rw(open(src, encoding='utf-8', errors='replace').read()))
    else:
        shutil.copyfile(src, dst)
    if ct: open(dst + '.ct', 'w').write(ct)

os.makedirs(M, exist_ok=True)
for pkg, ver, d in [('@shoelace-style/shoelace', '2.20.1', 'shoelace'), ('lucide', '1.17.0', 'lucide'),
                    ('@fontsource/poppins', '5.2.7', 'poppins'), ('fflate', '0.8.2', 'fflate')]:
    tgz = f'{M}/{d}.tgz'
    if not os.path.isdir(f'{M}/{d}'):
        name = pkg.split('/')[-1]
        sh(f'curl -sL --max-time 120 -o {tgz} https://registry.npmjs.org/{pkg}/-/{name}-{ver}.tgz')
        os.makedirs(f'{M}/{d}', exist_ok=True); sh(f'tar xzf {tgz} -C {M}/{d}')
for f in ('qry.js', 'qry-ui.css', 'qry-kit.js'):
    sh(f'curl -sL --max-time 60 -o {M}/{f} https://raw.githubusercontent.com/Bloechle/qry-js/{QRY_TAG}/{f}')

for f, ct in [('qry.js', 'application/javascript'), ('qry-ui.css', 'text/css'), ('qry-kit.js', 'application/javascript')]:
    seed(f'gh/Bloechle/qry-js@{QRY_TAG}/{f}', f'{M}/{f}', ct)
seed('npm/lucide@1.17.0/dist/umd/lucide.min.js', f'{M}/lucide/package/dist/umd/lucide.min.js', 'application/javascript')
for w in ('600', '700'):
    seed(f'npm/@fontsource/poppins@5/{w}.css', f'{M}/poppins/package/{w}.css', 'text/css')
for src in glob.glob(f'{M}/poppins/package/files/*'):
    seed(f'npm/@fontsource/poppins@5/files/{os.path.basename(src)}', src, 'font/woff2')
base = f'{M}/shoelace/package/cdn'
for src in glob.glob(base + '/**/*', recursive=True):
    if os.path.isfile(src):
        rel = 'npm/@shoelace-style/shoelace@2.20.1/cdn/' + os.path.relpath(src, base).replace('\\', '/')
        ct = 'text/css' if src.endswith('.css') else 'application/javascript' if src.endswith('.js') else None
        seed(rel, src, ct)
seed('npm/fflate@0.8.2/+esm', f'{M}/fflate/package/esm/browser.js', 'application/javascript')
print('seeded →', CDN)
