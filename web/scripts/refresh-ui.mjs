// Sources: official MIT-licensed shadcn/ui registry, new-york-v4.
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../src/components/ui/', import.meta.url));
await mkdir(root, { recursive: true });
const names = ['button', 'input', 'textarea', 'label', 'badge', 'dialog', 'sheet', 'switch', 'tabs', 'checkbox', 'separator', 'table', 'slider', 'tooltip', 'alert', 'progress', 'skeleton'];
await Promise.all(names.map(async (name) => {
  const response = await fetch(`https://ui.shadcn.com/r/styles/new-york-v4/${name}.json`);
  if (!response.ok) throw new Error(`${name}: ${response.status}`);
  const item = await response.json();
  for (const file of item.files || []) {
    const basename = file.path.split('/').pop();
    if (!/^[a-z-]+\.tsx$/.test(basename)) throw new Error(`Unexpected registry file: ${file.path}`);
    const source = file.content.replaceAll('from "cn"', 'from "@/lib/utils"').replaceAll('from "@/registry/new-york-v4/ui/', 'from "@/components/ui/');
    await writeFile(`${root}/${basename}`, `// shadcn/ui (MIT). Imported from the official new-york-v4 registry.\n${source}`);
  }
  console.log(name);
}));
