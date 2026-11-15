def parse_toml(text):
    sections = {}
    current = None
    for line in text.split('\n'):
        stripped = line.strip()
        if stripped.startswith('[') and stripped.endswith(']'):
            current = stripped[1:-1]
            sections[current] = []
        elif current and stripped and not stripped.startswith('#'):
            sections[current].append(line)
    return sections

def get_keys(lines):
    return set(line.split('=')[0].strip() for line in lines if '=' in line)

with open('gradle/libs.versions.toml') as f:
    tvbox = parse_toml(f.read())
with open('../media/gradle/libs.versions.toml') as f:
    media3 = parse_toml(f.read())

output = []
for section in ['versions', 'libraries', 'plugins', 'bundles']:
    if section in tvbox or section in media3:
        output.append(f'[{section}]')
        tvbox_keys = get_keys(tvbox.get(section, []))
        merged = tvbox.get(section, []) + [
            line for line in media3.get(section, [])
            if '=' not in line or line.split('=')[0].strip() not in tvbox_keys
        ]
        output.extend(merged)
        output.append('')

with open('gradle/libs.versions.toml', 'w') as f:
    f.write('\n'.join(output))
