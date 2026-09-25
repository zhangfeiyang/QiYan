import { readFile, readdir } from 'node:fs/promises';

/** Android merges every values XML file; shared strings must use the same resource scope. */
export async function readAndroidStrings(directory) {
  const strings = {};
  const files = (await readdir(directory)).filter((name) => name.endsWith('.xml')).sort();
  for (const file of files) {
    const xml = await readFile(`${directory}/${file}`, 'utf8');
    for (const match of xml.matchAll(/<string\b([^>]*)>([\s\S]*?)<\/string>/g)) {
      const key = /\bname="([^"]+)"/.exec(match[1])?.[1];
      if (!key) continue;
      if (key in strings) throw new Error(`Duplicate Android string ${key} in ${directory}/${file}`);
      strings[key] = match[2]
        .replace(/\\n/g, '\n').replace(/\\'/g, "'").replace(/\\"/g, '"')
        .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'").replace(/&amp;/g, '&');
    }
  }
  return strings;
}
