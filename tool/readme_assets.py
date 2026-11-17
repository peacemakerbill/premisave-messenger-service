#!/usr/bin/env python3
"""Draws the pictures used by README.md into docs/assets.

    python3 tool/readme_assets.py

Only the Python standard library is needed. The pictures are plain SVG with CSS animation, so GitHub shows them as
they are, with no scripts and no outside files. The type is embedded from tool/fonts (small subsets of IBM Plex Sans,
IBM Plex Sans Condensed and JetBrains Mono, all under the SIL Open Font License), so the lettering looks the same on every
computer. The numbers in the "stats" picture are counted from the real source files, so run this again after a big change.
"""
import base64
import math
import os
import re
import textwrap

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'docs', 'assets')
FONTS = os.path.join(ROOT, 'tool', 'fonts')

# The look: a drafting sheet. Cool paper, green black ink, one amber for the thing that matters.
PAPER = '#F4F6F3'
PAPER2 = '#E8EDE9'
INK = '#0F2A22'
INK2 = '#4C655B'
RULE = '#BAC7C0'
DATA = '#1F6B55'      # the one colour the charts use
AMBER = '#E3A72F'
AMBER_INK = '#8A5F0A'  # amber that stays readable as text on paper
SHEET = '#0B2F25'      # the dark sheet, banner only
SHEET_LINE = '#DCE8E1'
SHEET_SOFT = '#7FA797'

SANS = "'PS Sans','IBM Plex Sans','Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif"
COND = "'PS Cond','IBM Plex Sans Condensed','Arial Narrow','Helvetica Neue',Arial,sans-serif"
MONO = "'PS Mono','JetBrains Mono',ui-monospace,SFMono-Regular,Menlo,Consolas,monospace"

FACES = {
    'cond7': ('PS Cond', 700, 'plex-cond-700.woff2'),
    'cond6': ('PS Cond', 600, 'plex-cond-600.woff2'),
    'sans4': ('PS Sans', 400, 'plex-400.woff2'),
    'sans6': ('PS Sans', 600, 'plex-600.woff2'),
    'mono4': ('PS Mono', 400, 'mono-400.woff2'),
    'mono6': ('PS Mono', 600, 'mono-600.woff2'),
}
_cache = {}


def font_css(*keys):
    out = []
    for k in keys:
        fam, weight, fname = FACES[k]
        if fname not in _cache:
            with open(os.path.join(FONTS, fname), 'rb') as f:
                _cache[fname] = base64.b64encode(f.read()).decode('ascii')
        out.append(f"@font-face{{font-family:'{fam}';font-weight:{weight};src:url(data:font/woff2;base64,{_cache[fname]}) format('woff2');}}")
    return ''.join(out)


BASE_CSS = f"""
text {{ font-family: {SANS}; fill: {INK}; }}
.cond {{ font-family: {COND}; font-weight: 700; }}
.mono {{ font-family: {MONO}; }}
.soft {{ fill: {INK2}; }}
@media (prefers-reduced-motion: reduce) {{ * {{ animation: none !important; }} }}
"""


def esc(s):
    return str(s).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def svg(width, height, body, css='', defs='', title='', desc='', fonts=('cond7', 'sans4', 'sans6')):
    head = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img" aria-labelledby="t d">'
    meta = f'<title id="t">{esc(title)}</title><desc id="d">{esc(desc or title)}</desc>'
    return f'{head}{meta}<defs>{defs}</defs><style>{font_css(*fonts)}{BASE_CSS}{css}</style>{body}</svg>\n'


def save(name, content):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f:
        f.write(content)
    print('wrote', name, len(content) // 1024, 'KB')


def sheet(w, h, title, note=None):
    """A light drawing sheet: paper, an inset border, a heading and the rule under it."""
    out = (f'<rect width="{w}" height="{h}" fill="{PAPER}"/>'
           f'<rect x="8.5" y="8.5" width="{w - 17}" height="{h - 17}" fill="none" stroke="{INK}" stroke-width="1"/>'
           f'<text class="cond" x="32" y="52" font-size="26">{esc(title)}</text>')
    if note:
        out += f'<text class="soft" x="{w - 32}" y="50" text-anchor="end" font-size="13.5">{esc(note)}</text>'
    out += f'<line x1="32" y1="68" x2="{w - 32}" y2="68" stroke="{INK}" stroke-width="1.5"/>'
    return out


def num(x, y, n, r=11, fill=INK, ink=PAPER):
    """A numbered callout, the circle architects use to point at a detail."""
    return (f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}"/>'
            f'<text x="{x}" y="{y + 4.4}" text-anchor="middle" font-size="12.5" font-weight="600" style="fill:{ink}" class="mono">{n}</text>')


# ---------------------------------------------------------------------------------------- motion helpers
#
# Every loop is plain CSS, so a device that asks for reduced motion stops all of it (BASE_CSS). The rule that keeps
# that safe: an element's own style is always its finished, readable state, and the keyframes only add the motion.

def pc(t, cycle):
    return f'{min(max(t / cycle * 100, 0), 100):.3f}%'


def kf(name, cycle, frames):
    """Keyframes from [(seconds, 'declarations')], written as percentages of the cycle."""
    frames = sorted(frames, key=lambda f: f[0])
    return f'@keyframes {name} {{ ' + ' '.join(f'{pc(t, cycle)} {{ {s} }}' for t, s in frames) + ' }\n'


def lit_kf(name, cycle, base, on, wins, fade=0.25):
    """Keyframes that sit on `base` and switch to `on` during each (start, end) window, in seconds."""
    frames = []
    for s, e in wins:
        frames += [(s, base), (s + fade, on), (e, on), (e + fade, base)]
    if not frames or frames[0][0] > 0:
        frames.insert(0, (0, base))
    if frames[-1][0] < cycle:
        frames.append((cycle, base))
    return kf(name, cycle, [(min(t, cycle), s) for t, s in frames])


def band_kf(name, cycle, n, hold, pitch, axis='Y', glide=0.4):
    """A highlight band that rests on item after item (n items, `pitch` px apart) and then fades."""
    def at(k, op):
        return f'opacity:{op};transform:translate{axis}({k * pitch}px)'
    frames = [(0, at(0, 0)), (0.3, at(0, 1))]
    for k in range(n):
        frames += [(k * hold + (0.3 if k == 0 else 0), at(k, 1)), ((k + 1) * hold - glide, at(k, 1))]
    frames += [(n * hold - glide + 0.3, at(n - 1, 0)), (cycle, at(n - 1, 0))]
    return kf(name, cycle, frames)


# ---------------------------------------------------------------------------------------- small pieces

def logo_mark(x, y, s):
    """The Premisave mark: a cream house beside an amber house, on a 1024 unit grid."""
    wins = ''.join(f'<rect class="lgw" style="--d:{1.1 + k * 0.12:.2f}s" x="{wx}" y="{wy}" width="48" height="48" fill="{SHEET}"/>'
                   for k, (wx, wy) in enumerate(((582, 541), (649, 541), (582, 608), (649, 608))))
    return (f'<g transform="translate({x} {y}) scale({s})">'
            f'<path class="lg1" d="M282 742V474L512 282V742H445V617A48 48 0 0 0 349 617V742Z" fill="{SHEET_LINE}"/>'
            f'<path class="lg2" d="M536 742V302L742 474V742Z" fill="{AMBER}"/>{wins}</g>')


def bubble(x, y, w, h, tail, r=14):
    """A speech bubble outline. tail is 'l' or 'r'."""
    if tail == 'l':
        return (f'M{x + r} {y}H{x + w - r}a{r} {r} 0 0 1 {r} {r}V{y + h - r}a{r} {r} 0 0 1 -{r} {r}'
                f'H{x + 26}L{x - 6} {y + h + 12}L{x} {y + h - 6}V{y + r}a{r} {r} 0 0 1 {r} -{r}Z')
    return (f'M{x + r} {y}H{x + w - r}a{r} {r} 0 0 1 {r} {r}V{y + h - 6}L{x + w + 6} {y + h + 12}L{x + w - 26} {y + h}'
            f'H{x + r}a{r} {r} 0 0 1 -{r} -{r}V{y + r}a{r} {r} 0 0 1 {r} -{r}Z')


def hop_kf(name, cycle, t0, dur, dist, axis='X'):
    """A packet that waits, travels `dist` px during [t0, t0 + dur] and vanishes."""
    a, b = t0 / cycle * 100, (t0 + dur) / cycle * 100
    return (f'@keyframes {name} {{ 0%, {a:.3f}% {{ opacity: 0; transform: translate{axis}(0px); }} '
            f'{a + 0.3:.3f}% {{ opacity: 1; transform: translate{axis}(0px); animation-timing-function: cubic-bezier(.55,0,.35,1); }} '
            f'{b - 0.3:.3f}% {{ opacity: 1; transform: translate{axis}({dist:.1f}px); }} '
            f'{b:.3f}%, 100% {{ opacity: 0; transform: translate{axis}({dist:.1f}px); }} }}\n')


# ---------------------------------------------------------------------------------------- banner

def banner():
    w, h = 1280, 420
    lines, t = [], [0.0]

    def draw(d, delay, width=1.6, colour=SHEET_LINE, extra=''):
        lines.append(f'<path class="dr" pathLength="1" d="{d}" fill="none" stroke="{colour}" stroke-width="{width}" stroke-linejoin="round" '
                     f'stroke-linecap="round" style="animation-delay:{delay:.2f}s"{extra}/>')

    # an incoming bubble, an outgoing bubble, a typing bubble and a call tile that is being recorded
    draw(bubble(776, 96, 250, 58, 'l'), 0.4)
    draw('M796 116h150M796 134h96', 0.8, 1.2, SHEET_SOFT)
    draw(bubble(988, 178, 250, 58, 'r'), 0.7)
    draw('M1008 198h170M1008 216h104', 1.1, 1.2, SHEET_SOFT)
    draw(bubble(776, 262, 92, 40, 'l', 12), 1.0)
    draw('M892 262H1238V380H892Z', 1.3, 1.6)
    draw('M892 286H1238', 1.6, 1.0, SHEET_SOFT)
    # two people in the call tile, drawn as head and shoulders
    for k, cx in enumerate((970, 1086)):
        draw(f'M{cx - 24} 366a24 24 0 0 1 48 0', 1.7 + k * 0.15, 1.3)
        draw(f'M{cx - 10} 318a10 10 0 1 0 20 0a10 10 0 1 0 -20 0', 1.8 + k * 0.15, 1.3)
    draw('M1166 360h56v-44h-56z', 2.0, 1.2, SHEET_SOFT)
    ticks = (f'<g class="fade" style="animation-delay:2.1s"><path class="tk1" d="M1186 226l6 6l12 -13" fill="none" stroke="{SHEET_SOFT}" stroke-width="2"/>'
             f'<path class="tk2" d="M1200 226l6 6l12 -13" fill="none" stroke="{SHEET_SOFT}" stroke-width="2"/></g>')
    dots = ''.join(f'<circle class="td" style="--i:{i}" cx="{800 + i * 20}" cy="282" r="4" fill="{SHEET_LINE}"/>' for i in range(3))
    rec = (f'<circle class="recdot" cx="910" cy="274" r="5" fill="{AMBER}"/>'
           f'<text x="922" y="278" font-size="12" class="mono" style="fill:{AMBER}">rec 00:42</text>')
    gridlines = ''.join(f'M{x} 0V{h}' for x in range(40, w, 40)) + ''.join(f'M0 {y}H{w}' for y in range(40, h, 40))
    tags = ['STOMP', 'REST', 'Redis', 'MongoDB', 'LiveKit']
    px, tg = 68, ''
    for k, p in enumerate(tags):
        tg += f'<text x="{px}" y="372" font-size="14.5" class="mono tg" style="fill:{SHEET_SOFT};--i:{k}">{p}</text>'
        px += len(p) * 8.7 + 24
        if k < len(tags) - 1:
            tg += f'<path d="M{px - 12} 360v14" stroke="{SHEET_SOFT}" stroke-width="1" opacity=".6"/>'
    css = f"""
.dr {{ stroke-dasharray: 1; animation: draw 1.4s cubic-bezier(.5,0,.2,1) both; }}
@keyframes draw {{ from {{ stroke-dashoffset: 1; }} to {{ stroke-dashoffset: 0; }} }}
.fade {{ animation: fadein 1s both; }} @keyframes fadein {{ from {{ opacity: 0; }} }}
.td {{ animation: type 1.5s calc(var(--i) * .18s) ease-in-out infinite; }}
@keyframes type {{ 0%, 60%, 100% {{ opacity: .3; }} 30% {{ opacity: 1; }} }}
.recdot {{ animation: rec 1.6s ease-in-out infinite; }} @keyframes rec {{ 0%, 100% {{ opacity: 1; }} 50% {{ opacity: .25; }} }}
.tk2 {{ animation: seen 6s 3s infinite; }}
@keyframes seen {{ 0%, 8% {{ stroke: {SHEET_SOFT}; }} 14%, 70% {{ stroke: {AMBER}; }} 80%, 100% {{ stroke: {SHEET_SOFT}; }} }}
.in1 {{ animation: slide .9s .1s cubic-bezier(.2,.7,.2,1) both; }} .in2 {{ animation: slide .9s .35s cubic-bezier(.2,.7,.2,1) both; }}
@keyframes slide {{ from {{ opacity: 0; transform: translateY(10px); }} }}
.lg1, .lg2 {{ animation: lgrise 1s cubic-bezier(.2,.8,.2,1) both; }} .lg2 {{ animation-delay: .25s; }}
@keyframes lgrise {{ from {{ opacity: 0; transform: translateY(90px); }} }}
.lgw {{ transform-box: fill-box; transform-origin: center; animation: pop .5s var(--d) cubic-bezier(.3,1.5,.5,1) both; }}
@keyframes pop {{ from {{ transform: scale(0); }} }}
.tg {{ animation: tick 7.5s calc(2.4s + var(--i) * 1.3s) infinite; }}
@keyframes tick {{ 0%, 100% {{ fill: {SHEET_SOFT}; }} 2%, 14% {{ fill: {AMBER}; }} 17% {{ fill: {SHEET_SOFT}; }} }}
text {{ fill: {SHEET_LINE}; }}
"""
    body = (f'<rect width="{w}" height="{h}" fill="{SHEET}"/>'
            f'<path d="{gridlines}" stroke="#fff" stroke-width="1" opacity=".045" fill="none"/>'
            f'{"".join(lines)}{ticks}<g class="fade" style="animation-delay:2s">{dots}{rec}</g>'
            f'<g class="in1">{logo_mark(7.6, 150 - 742 * 0.2, 0.2)}<text class="cond" x="176" y="150" font-size="84" letter-spacing="-1.5">Premisave</text>'
            f'<text class="cond" x="66" y="234" font-size="84" letter-spacing="-1.5">Messenger</text></g>'
            f'<g class="in2"><text x="68" y="290" font-size="24" style="fill:{SHEET_LINE}">Chat, presence and calls for the Premisave platform.</text>'
            f'<text x="68" y="320" font-size="15.5" style="fill:{SHEET_SOFT}">Spring Boot, MongoDB, Redis and LiveKit. One Docker image for every environment.</text>'
            f'{tg}</g>')
    save('banner.svg', svg(w, h, body, css, '', 'Premisave Messenger service',
                           'The Premisave logo and name beside a line drawing of chat bubbles and a recorded video call tile.'))


def divider():
    w, h = 1200, 22
    ticks = ''.join(f'M{x} 11v{-7 if x % 50 == 0 else -4}' for x in range(8, 1193, 10))
    body = (f'<line x1="8" y1="11" x2="1192" y2="11" stroke="#8FA39A" stroke-width="1.2"/>'
            f'<path d="{ticks}" stroke="#8FA39A" stroke-width="1" fill="none"/>'
            f'<rect class="mk" x="595" y="5" width="10" height="12" fill="{AMBER}"/>')
    css = ('.mk { animation: slide 18s ease-in-out infinite alternate; } '
           '@keyframes slide { from { transform: translateX(-560px); } to { transform: translateX(560px); } }')
    save('divider.svg', svg(w, h, body, css, '', 'Divider', fonts=()))


# ---------------------------------------------------------------------------------------- architecture

def architecture():
    w, h = 1200, 560
    cycle = 11.0
    css = ('.rise { animation: rise .7s cubic-bezier(.2,.8,.2,1) both; } @keyframes rise { from { opacity:0; transform: translateY(14px); } }\n'
           '.pk, .pl { opacity: 0; }\n')
    cx, cw, cy, ch = 350, 380, 96, 374
    deps = [
        ('MongoDB', 'chats, messages, calls', 'Spring Data'),
        ('Redis', 'relay, presence, cache', 'pub/sub'),
        ('auth-service', 'accounts and profiles', 'Feign, JWT'),
        ('File storage', 'local folder or share', 'attachments'),
        ('LiveKit', 'audio, video, recording', 'tokens, rooms'),
    ]
    dx, dw, dh, gap0 = 850, 318, 62, 16
    out = [f'<g class="rise"><rect x="{cx}" y="{cy}" width="{cw}" height="{ch}" fill="{PAPER}" stroke="{INK}" stroke-width="2"/>'
           f'<rect x="{cx + 5}" y="{cy + 5}" width="{cw - 10}" height="{ch - 10}" fill="none" stroke="{INK}" stroke-width=".8"/>'
           f'<text class="cond" x="{cx + 24}" y="{cy + 42}" font-size="26">Messenger service</text>'
           f'<text class="mono" x="{cx + cw - 24}" y="{cy + 40}" text-anchor="end" font-size="12" style="fill:{DATA}">:8081</text></g>']
    inner = [('Chats and groups', 'private, groups, invite links'), ('Messages and files', 'send, edit, react, attach'),
             ('Presence and statuses', 'online, last seen, statuses'), ('Calls', 'tokens, ringing, history, recordings')]
    for i, (a, b) in enumerate(inner):
        y = cy + 62 + i * 58
        out.append(f'<g class="rise" style="animation-delay:{.15 + i * .08:.2f}s"><rect x="{cx + 22}" y="{y}" width="{cw - 44}" height="50" fill="none" stroke="{INK}" stroke-width="1.2"/>'
                   f'<text class="cond" x="{cx + 38}" y="{y + 22}" font-size="17">{a}</text><text x="{cx + 38}" y="{y + 40}" font-size="12.5" class="soft">{b}</text></g>')
    out.append(f'<text x="{cx + 24}" y="{cy + ch - 66}" font-size="13" font-weight="600">REST at /api, STOMP at /ws-messenger</text>'
               f'<text x="{cx + 24}" y="{cy + ch - 42}" font-size="12.5" class="soft">No session on the server, so any copy can serve any person.</text>'
               f'<text x="{cx + 24}" y="{cy + ch - 22}" font-size="12.5" class="soft">Redis passes live messages between copies.</text>')
    clients = [('Flutter app', 'Android, iOS, web, desktop', 150), ('Browser client', 'test-client/index.html', 300)]
    for i, (a, b, y) in enumerate(clients):
        out.append(f'<g class="rise" style="animation-delay:{i * .1:.2f}s"><rect x="32" y="{y}" width="200" height="96" fill="{PAPER}" stroke="{INK}" stroke-width="2"/>'
                   f'<text class="cond" x="50" y="{y + 40}" font-size="23">{a}</text><text x="50" y="{y + 64}" font-size="12.5" class="soft">{b}</text></g>')
        ym = y + 48
        out.append(f'<path d="M232 {ym}H{cx - 2}" stroke="{INK}" stroke-width="1.5"/><path d="M{cx - 10} {ym - 5}L{cx - 2} {ym}L{cx - 10} {ym + 5}Z" fill="{INK}"/>'
                   f'<text class="mono" x="{(232 + cx) / 2}" y="{ym - 8}" text-anchor="middle" font-size="11" style="fill:{INK2}">JWT</text>')
        out.append(f'<rect class="pk" x="236" y="{ym - 4}" width="8" height="8" fill="{AMBER}" stroke="{INK}" style="animation: c{i} {cycle}s infinite"/>')
        css += hop_kf(f'c{i}', cycle, 0.0 + i * 0.3, 0.9, cx - 252)
    dep_y = []
    for i, (a, b, lab) in enumerate(deps):
        y = cy + i * (dh + gap0)
        ym = y + dh / 2
        dep_y.append((y, ym))
        solid = i == 2
        out.append(f'<g class="rise" style="animation-delay:{.1 + i * .07:.2f}s"><rect x="{dx}" y="{y}" width="{dw}" height="{dh}" fill="{INK if solid else PAPER}" stroke="{INK}" stroke-width="2"/>'
                   f'<text class="cond" x="{dx + 18}" y="{y + 28}" font-size="20" style="fill:{PAPER if solid else INK}">{a}</text>'
                   f'<text x="{dx + 18}" y="{y + 48}" font-size="12.5" style="fill:{SHEET_LINE if solid else INK2}">{b}</text></g>')
        out.append(f'<path d="M{cx + cw} {ym}H{dx - 2}" stroke="{INK}" stroke-width="1.5"/><path d="M{dx - 10} {ym - 5}L{dx - 2} {ym}L{dx - 10} {ym + 5}Z" fill="{INK}"/>'
                   f'<text class="mono" x="{(cx + cw + dx) / 2}" y="{ym - 8}" text-anchor="middle" font-size="11" style="fill:{INK2}">{lab}</text>')
        t0 = 1.2 + i * 1.7
        out.append(f'<rect class="pk" x="{cx + cw + 4}" y="{ym - 4}" width="8" height="8" fill="{AMBER}" stroke="{INK}" style="animation: d{i} {cycle}s infinite"/>')
        css += hop_kf(f'd{i}', cycle, t0, 1.0, dx - cx - cw - 20)
        out.append(f'<rect class="pl" x="{dx - 3}" y="{y - 3}" width="{dw + 6}" height="{dh + 6}" fill="none" stroke="{AMBER}" stroke-width="5" style="animation: p{i} {cycle}s infinite"/>')
        css += lit_kf(f'p{i}', cycle, 'opacity:0', 'opacity:1', [(t0 + 0.8, t0 + 1.9)])
    out.append(f'<text x="32" y="{h - 36}" font-size="13.5" font-weight="600">The messenger owns chats, messages, groups, files, statuses and calls. It never stores a password: who a person is comes from auth-service.</text>')
    body = sheet(w, h, 'Where the messenger sits', 'one service, five things it depends on') + ''.join(out)
    save('architecture.svg', svg(w, h, body, css, '', 'Architecture of the messenger service',
                                 'Flutter and browser clients call the messenger over REST and STOMP. The messenger uses MongoDB, Redis, auth-service, file storage and LiveKit.',
                                 ('cond7', 'sans4', 'sans6', 'mono4', 'mono6')))


# ---------------------------------------------------------------------------------------- sequence pictures

def lanes_picture(fname, title, note, lanes, steps, foot, h, alt, top=168, gap=66, cycle=16.0):
    """A sequence diagram: lanes across, one step per row, a packet that crosses each arrow in turn.

    lanes: [(name, x)], steps: [(from, to, label, note, is_answer)]. A step whose from equals to is shown as work done
    inside one lane.
    """
    w = 1200
    css, drawn, marks = '.cur, .pk { opacity: 0; }\n', [], []
    lane_svg = ''
    for n, x in lanes:
        lane_svg += (f'<text class="cond" x="{x}" y="104" text-anchor="middle" font-size="20">{esc(n)}</text>'
                     f'<line x1="{x - 74}" y1="116" x2="{x + 74}" y2="116" stroke="{INK}" stroke-width="1.5"/>'
                     f'<line x1="{x}" y1="116" x2="{x}" y2="{h - 96}" stroke="{INK2}" stroke-width="1" stroke-dasharray="3 5"/>')
    total = len(steps) + 0.6
    for i, (a, b, label, sub, back) in enumerate(steps):
        y = top + i * gap
        xa, xb = lanes[a][1], lanes[b][1]
        if a == b:
            body = (f'<rect x="{xa - 6}" y="{y - 24}" width="12" height="54" fill="{PAPER}" stroke="{INK}" stroke-width="1.4"/>'
                    f'<path d="M{xa + 6} {y - 4}h24v22h-24" fill="none" stroke="{INK}" stroke-width="1.4"/><path d="M{xa + 11} {y + 14}l-5 4" stroke="{INK}" stroke-width="1.4"/>'
                    f'<text class="mono" x="{xa + 42}" y="{y + 2}" font-size="13" font-weight="600">{esc(label)}</text>'
                    f'<text x="{xa + 42}" y="{y + 21}" font-size="12.5" class="soft">{esc(sub)}</text>')
        else:
            d = 1 if xb > xa else -1
            mid = (xa + xb) / 2
            dash = ' stroke-dasharray="6 4"' if back else ''
            body = (f'<path d="M{xa + 8 * d} {y}H{xb - 12 * d}" stroke="{INK}" stroke-width="1.6"{dash}/>'
                    f'<path d="M{xb - 14 * d} {y - 6}L{xb - 2 * d} {y}L{xb - 14 * d} {y + 6}" fill="none" stroke="{INK}" stroke-width="1.6"/>'
                    f'<text class="mono" x="{mid}" y="{y - 10}" text-anchor="middle" font-size="13" font-weight="600">{esc(label)}</text>'
                    f'<text x="{mid}" y="{y + 22}" text-anchor="middle" font-size="12.5" class="soft">{esc(sub)}</text>')
        drawn.append(f'<g class="s{i}">{num(40, y, i + 1)}{body}</g>')
        on = i / total * 100
        css += f'.s{i} {{ animation: st{i} {cycle}s infinite; }}\n@keyframes st{i} {{ 0%,{on:.1f}% {{ opacity: 0; }} {on + 3.0:.1f}%,93% {{ opacity: 1; }} 98%,100% {{ opacity: 0; }} }}\n'
        nxt = (i + 1) / total * 100 if i + 1 < len(steps) else 96.0
        marks.append(f'<g class="cur" style="animation: cur{i} {cycle}s infinite">{num(40, y, i + 1, 11, AMBER, INK)}</g>')
        css += f'@keyframes cur{i} {{ 0%,{on:.2f}% {{ opacity: 0; }} {on + 1.5:.2f}%,{nxt:.2f}% {{ opacity: 1; }} {nxt + 1.5:.2f}%,100% {{ opacity: 0; }} }}\n'
        if a != b:
            d = 1 if xb > xa else -1
            dist = (xb - 12 * d) - (xa + 8 * d)
            marks.append(f'<rect class="pk" x="{xa + 8 * d - 4}" y="{y - 4}" width="8" height="8" fill="{AMBER}" stroke="{INK}" stroke-width="1" style="animation: apk{i} {cycle}s infinite"/>')
            span = 100 / total * 0.85
            css += (f'@keyframes apk{i} {{ 0%,{on + 1.5:.2f}% {{ opacity: 0; transform: translateX(0px); }} '
                    f'{on + 1.9:.2f}% {{ opacity: 1; transform: translateX(0px); animation-timing-function: cubic-bezier(.55,0,.35,1); }} '
                    f'{on + span:.2f}% {{ opacity: 1; transform: translateX({dist}px); }} {on + span + 0.4:.2f}%,100% {{ opacity: 0; transform: translateX({dist}px); }} }}\n')
    rule = f'<line x1="32" y1="{h - 78}" x2="{w - 32}" y2="{h - 78}" stroke="{INK}" stroke-width="1.5"/>'
    foots = ''
    for k, t in enumerate(foot):
        cls = '' if k == 0 else ' class="soft"'
        foots += f'<text x="32" y="{h - 50 + k * 22}" font-size="14.5"{cls}>{esc(t)}</text>'
    body = sheet(w, h, title, note) + lane_svg + ''.join(drawn) + ''.join(marks) + rule + foots
    save(fname, svg(w, h, body, css, '', title, alt, ('cond7', 'sans4', 'sans6', 'mono4', 'mono6')))


def realtime():
    lanes = [("Ana's app", 110), ('Messenger, copy 1', 350), ('Redis', 590), ('Messenger, copy 2', 830), ("Ben's app", 1080)]
    steps = [
        (0, 1, 'SEND /app/chat.sendMessage', 'Ana sends a message', False),
        (1, 1, 'save the message', 'checks the chat, stores it in MongoDB', False),
        (1, 2, 'publish the envelope', 'userId, destination and payload', False),
        (2, 3, 'every copy receives it', 'each one tries to deliver it', False),
        (3, 4, 'MESSAGE /user/queue/messages', 'only the copy holding Ben can', False),
        (3, 1, 'delivery status, through Redis', 'back to the copy holding Ana', True),
        (1, 0, '/user/queue/delivery-status', 'Ana sees her message delivered', True),
    ]
    foot = ['Ana and Ben can be connected to different copies and still get every message, typing signal, read receipt and presence change.',
            'Without the relay, a message would only reach people connected to the same copy as the sender.']
    lanes_picture('realtime.svg', 'How a message is delivered', 'one message, two copies of the service', lanes, steps, foot, 690,
                  'A message goes from Ana to a messenger copy, through Redis to every copy, and the copy holding Ben delivers it. A delivery status returns to Ana the same way.',
                  gap=66)


def calls():
    lanes = [('The host', 120), ('Messenger', 440), ('LiveKit and recorder', 770), ('Storage', 1090)]
    steps = [
        (0, 1, 'POST /api/calls', 'start a call, the others are rung', False),
        (1, 2, 'create the room', 'and sign a token for each person', False),
        (1, 0, 'token and LiveKit address', 'media never passes through the messenger', True),
        (0, 2, 'audio and video', 'over WebRTC, straight to LiveKit', False),
        (0, 1, 'POST /api/calls/{id}/recording/start', 'host or co-host, switch on, storage ready', False),
        (1, 2, 'start the recorder', 'it joins the room and mixes the call', False),
        (2, 3, 'write the video file', 'a local folder or a mounted network share', False),
        (0, 1, 'GET /api/calls/recordings/{id}/file', 'people who were in the call can watch it', False),
    ]
    foot = ['If the storage is missing or short of space, recording refuses to start instead of losing the video.',
            'An administrator can switch recording off for everyone: Admin, Call settings.']
    lanes_picture('calls.svg', 'A call and its recording', 'from ringing to watching it again', lanes, steps, foot, 740,
                  'The host starts a call, the messenger signs tokens, audio and video go to LiveKit, the recorder writes a file to storage and the messenger plays it back.',
                  gap=62, cycle=19.0)


# ---------------------------------------------------------------------------------------- presence

def presence():
    w, h = 1200, 430
    cycle = 14.0
    cards = [('Tab opens', '0 to 1', 'First session. Marked online and sent to /topic/presence.', True),
             ('Phone connects', '1 to 2', 'Already online, so nothing is sent.', False),
             ('Tab closes', '2 to 1', 'The phone is still connected, so the person stays online.', False),
             ('Phone closes', '1 to 0', 'Last session gone. Marked offline and sent to /topic/presence.', True)]
    cw, step = 262, 286
    out, css = [], ''
    for i, (a, b, c, shout) in enumerate(cards):
        x = 32 + i * step
        out.append(f'<g class="rise" style="animation-delay:{i * .1:.2f}s"><rect x="{x}" y="100" width="{cw}" height="160" fill="{INK if shout else PAPER}" stroke="{INK}" stroke-width="2"/>'
                   f'<text class="cond" x="{x + 18}" y="138" font-size="22" style="fill:{PAPER if shout else INK}">{a}</text>'
                   f'<text class="mono" x="{x + 18}" y="176" font-size="26" font-weight="600" style="fill:{AMBER if shout else DATA}">{b}</text>'
                   + ''.join(f'<text x="{x + 18}" y="{206 + k * 18}" font-size="12.5" style="fill:{SHEET_LINE if shout else INK2}">{esc(t)}</text>'
                             for k, t in enumerate(textwrap.wrap(c, 33))) +
                   num(x + 18, 100, i + 1, 12, AMBER, INK) + '</g>')
    # timeline under the cards: offline, online, offline
    c0, c3 = 32 + cw / 2, 32 + 3 * step + cw / 2
    out.append(f'<line x1="32" y1="340" x2="{c0}" y2="340" stroke="{RULE}" stroke-width="4"/>'
               f'<line x1="{c0}" y1="340" x2="{c3}" y2="340" stroke="{DATA}" stroke-width="6"/>'
               f'<line x1="{c3}" y1="340" x2="{w - 32}" y2="340" stroke="{RULE}" stroke-width="4"/>')
    for i in range(4):
        x = 32 + i * step + cw / 2
        out.append(f'<path d="M{x} 260V334" stroke="{INK2}" stroke-width="1" stroke-dasharray="2 4"/><circle cx="{x}" cy="340" r="6" fill="{PAPER}" stroke="{INK}" stroke-width="2"/>')
    out.append(f'<text x="32" y="372" font-size="13.5" class="soft">offline</text><text x="{(c0 + c3) / 2}" y="372" text-anchor="middle" font-size="13.5" font-weight="600" style="fill:{DATA}">online the whole time</text>'
               f'<text x="{w - 32}" y="372" text-anchor="end" font-size="13.5" class="soft">offline</text>')
    out.append(f'<g class="hi"><rect x="29" y="97" width="{cw + 6}" height="166" fill="none" stroke="{AMBER}" stroke-width="5"/></g>')
    css += '.rise { animation: rise .7s cubic-bezier(.2,.8,.2,1) both; } @keyframes rise { from { opacity:0; transform: translateY(14px); } }\n'
    css += '.hi { opacity: 0; animation: hi ' + f'{cycle}s infinite; }}\n'
    css += band_kf('hi', cycle, 4, 3.2, step, 'X')
    out.append(f'<text x="32" y="{h - 30}" font-size="13.5">The counter is presence:sessions:userId in Redis, so it stays right when the two sessions are on different copies of the service.</text>')
    body = sheet(w, h, 'How online and offline are decided', 'a session counter, not a flag') + ''.join(out)
    save('presence.svg', svg(w, h, body, css, '', 'Presence tracking',
                             'A person is online from the first connected session to the last, and only the first and last change are broadcast.',
                             ('cond7', 'sans4', 'sans6', 'mono4', 'mono6')))


# ---------------------------------------------------------------------------------------- deploy

def deploy():
    w, h = 1200, 520
    cycle = 12.0
    envs = [('development', 'profile dev', 'branch develop, tag development'),
            ('staging', 'profile staging', 'branch staging, tag staging'),
            ('production', 'profile prod', 'branch main, tag production or 1.0.0')]
    # which platforms ship a file for which environment (read from the files that really exist)
    def has(*names):
        return all(os.path.exists(os.path.join(ROOT, n)) for n in names)
    plats = [
        ('Docker Compose', ['docker-compose.yml', '+ .staging.yml', '+ .production.yml'], (has('docker-compose.yml'), has('deploy/docker-compose.staging.yml'), has('deploy/docker-compose.production.yml'))),
        ('Coolify', ['docker-compose', '.coolify.yml'], (has('deploy/docker-compose.coolify.yml'),) * 3),
        ('Render', ['render.yaml'], (has('deploy/render.yaml'),) * 3),
        ('Heroku', ['heroku.yml', 'app.json'], (has('heroku.yml', 'app.json'),) * 3),
        ('Fly.io', ['fly.staging.toml', 'fly.production.toml'], (False, has('deploy/fly.staging.toml'), has('deploy/fly.production.toml'))),
        ('Railway', ['railway.json'], (has('deploy/railway.json'),) * 3),
    ]
    lx, colw, top, rowh = 32, 160, 176, 58
    x0 = 212
    out = [f'<text class="cond" x="{lx}" y="108" font-size="20">Environment</text>']
    for j, (name, files, _) in enumerate(plats):
        x = x0 + j * colw
        out.append(f'<text class="cond" x="{x + 6}" y="108" font-size="20">{name}</text>')
        for k, f in enumerate(files):
            out.append(f'<text class="mono soft" x="{x + 6}" y="{126 + k * 14}" font-size="10.5">{esc(f)}</text>')
    out.append(f'<line x1="{lx}" y1="{top - 14}" x2="{w - 32}" y2="{top - 14}" stroke="{INK}" stroke-width="1.5"/>')
    for i, (name, prof, note) in enumerate(envs):
        y = top + i * rowh
        out.append(f'<text class="cond" x="{lx}" y="{y + 20}" font-size="22">{name}</text>'
                   f'<text class="mono" x="{lx}" y="{y + 38}" font-size="11.5" style="fill:{DATA}">{prof}</text>'
                   f'<line x1="{lx}" y1="{y + rowh - 6}" x2="{w - 32}" y2="{y + rowh - 6}" stroke="{RULE}"/>')
        for j, (_, _, flags) in enumerate(plats):
            x = x0 + j * colw + 6
            if flags[i]:
                out.append(f'<rect x="{x}" y="{y + 8}" width="22" height="22" fill="{DATA}"/>'
                           f'<path d="M{x + 5} {y + 19}l5 5l8 -10" fill="none" stroke="{PAPER}" stroke-width="2.4"/>')
            else:
                out.append(f'<rect x="{x}" y="{y + 8}" width="22" height="22" fill="none" stroke="{RULE}" stroke-width="1.5"/>'
                           f'<path d="M{x + 6} {y + 19}h10" stroke="{RULE}" stroke-width="1.6"/>')
    css = ('.rise { animation: rise .7s cubic-bezier(.2,.8,.2,1) both; } @keyframes rise { from { opacity:0; transform: translateY(14px); } }\n'
           f'.band {{ opacity: 0; animation: band {cycle}s infinite; }}\n' + band_kf('band', cycle, 3, 3.6, rowh, 'Y'))
    out.append(f'<rect class="band" x="{lx - 6}" y="{top - 8}" width="{w - 52}" height="{rowh - 8}" fill="none" stroke="{AMBER}" stroke-width="4"/>')
    # the one image and the pair
    by = top + 3 * rowh + 26
    out.append(f'<line x1="32" y1="{by - 12}" x2="{w - 32}" y2="{by - 12}" stroke="{INK}" stroke-width="1.5"/>')
    boxw = 540
    for k, (a, b, c) in enumerate([
        ('One image', 'Dockerfile and docker/entrypoint.sh', 'Listens on PORT, runs as an unprivileged user, and reads SPRING_PROFILES_ACTIVE.'),
        ('A pair per environment', 'messenger and auth-service', 'JWT_SECRET identical. INTERNAL_API_KEY equals auth API_KEY. Own databases.')]):
        x = 32 + k * (boxw + 56)
        out.append(f'<g class="rise" style="animation-delay:{.2 + k * .12:.2f}s"><rect x="{x}" y="{by + 6}" width="{boxw}" height="104" fill="{PAPER if k else INK}" stroke="{INK}" stroke-width="2"/>'
                   f'<text class="cond" x="{x + 20}" y="{by + 40}" font-size="22" style="fill:{INK if k else PAPER}">{a}</text>'
                   f'<text class="mono" x="{x + 20}" y="{by + 62}" font-size="12" style="fill:{DATA if k else AMBER}">{b}</text>'
                   f'<text x="{x + 20}" y="{by + 88}" font-size="12.5" style="fill:{INK2 if k else SHEET_LINE}">{esc(c)}</text></g>')
    body = sheet(w, h, 'One image, every environment', 'what each platform ships') + ''.join(out)
    save('deploy.svg', svg(w, h, body, css, '', 'Deploying the messenger',
                           'One Docker image runs in development, staging and production on Docker Compose, Coolify, Render, Heroku, Fly.io or Railway.',
                           ('cond7', 'sans4', 'sans6', 'mono4', 'mono6')))


# ---------------------------------------------------------------------------------------- stats

PKG_NAME = {'service': 'Services', 'controller': 'Controllers', 'dto': 'Request and response objects', 'entity': 'MongoDB documents',
            'config': 'Configuration', 'repository': 'Repositories', 'security': 'Security', 'realtime': 'Redis relay',
            'storage': 'File storage', 'exception': 'Errors', 'client': 'auth-service client', 'util': 'Utilities', 'enums': 'Enums', 'event': 'Events'}


def java_stats():
    base = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'premisave', 'messenger')
    out = {}
    for dp, _, fs in os.walk(base):
        rel = os.path.relpath(dp, base)
        pkg = rel.split(os.sep)[0] if rel != '.' else 'root'
        for f in fs:
            if f.endswith('.java'):
                with open(os.path.join(dp, f), encoding='utf-8') as fh:
                    n = sum(1 for _ in fh)
                a = out.setdefault(pkg, [0, 0])
                a[0] += 1
                a[1] += n
    return out


def endpoints():
    n = 0
    base = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'premisave', 'messenger', 'controller')
    for f in os.listdir(base):
        with open(os.path.join(base, f), encoding='utf-8') as fh:
            n += len(re.findall(r'@(Get|Post|Put|Patch|Delete)Mapping', fh.read()))
    return n


def stats():
    data = java_stats()
    total_files = sum(v[0] for v in data.values())
    total_lines = sum(v[1] for v in data.values())
    items = sorted(((k, v) for k, v in data.items() if k != 'root'), key=lambda kv: -kv[1][1])
    top = items[:11]
    w = 1200
    row = 38
    h = 150 + len(top) * row + 40
    label_w, bar_x = 250, 300
    maxw = 640
    mx = top[0][1][1]
    out, css = [], '.g { transform-box: fill-box; transform-origin: left center; animation: grow 1.2s cubic-bezier(.2,.8,.2,1) both; } @keyframes grow { from { transform: scaleX(0); } }\n'
    for i, (k, (nf, nl)) in enumerate(top):
        y = 100 + i * row
        bw = max(4, nl / mx * maxw)
        out.append(f'<text class="cond" x="32" y="{y + 20}" font-size="16.5">{esc(PKG_NAME.get(k, k))}</text>'
                   f'<rect class="g" x="{bar_x}" y="{y + 4}" width="{bw:.1f}" height="22" fill="{DATA}" style="animation-delay:{i * .06:.2f}s"/>'
                   f'<text class="mono" x="{bar_x + bw + 10:.1f}" y="{y + 20}" font-size="12.5">{nl:,} lines, {nf} {"file" if nf == 1 else "files"}</text>')
    ys = 100 + len(top) * row + 18
    out.append(f'<line x1="32" y1="{ys - 8}" x2="{w - 32}" y2="{ys - 8}" stroke="{INK}" stroke-width="1.5"/>'
               f'<text x="32" y="{ys + 22}" font-size="14.5">{total_files} Java files and {total_lines:,} lines of Java under src/main/java. {endpoints()} REST endpoints in {len([f for f in os.listdir(os.path.join(ROOT, "src/main/java/com/premisave/messenger/controller"))])} controllers.</text>')
    body = sheet(w, h, 'Where the code lives', 'counted from the source') + ''.join(out)
    save('stats.svg', svg(w, h, body, css, '', 'Code statistics',
                          f'Bar chart of Java lines per package. {total_files} files and {total_lines} lines in total.', ('cond7', 'sans4', 'sans6', 'mono4', 'mono6')))


if __name__ == '__main__':
    banner()
    divider()
    architecture()
    realtime()
    presence()
    calls()
    deploy()
    stats()
