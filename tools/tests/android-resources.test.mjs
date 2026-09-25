import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { readAndroidStrings } from '../android-resources.mjs';

test('Android resource sync merges every XML and decodes string values', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'xiangqi-strings-'));
  try {
    await writeFile(join(directory, 'strings.xml'), '<resources><string name="legacy">Before &amp; after</string></resources>');
    await writeFile(join(directory, 'home_refresh.xml'), '<resources><string translatable="true" name="difficulty_gentle">Gentle\\nPractice</string></resources>');
    await writeFile(join(directory, 'colors.xml'), '<resources><color name="surface">#ffffff</color></resources>');
    assert.deepEqual(await readAndroidStrings(directory), { difficulty_gentle: 'Gentle\nPractice', legacy: 'Before & after' });
    await writeFile(join(directory, 'duplicate.xml'), '<resources><string name="legacy">Wrong copy</string></resources>');
    await assert.rejects(() => readAndroidStrings(directory), /Duplicate Android string legacy/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
