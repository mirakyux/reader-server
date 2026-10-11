import tseslint from 'typescript-eslint';
import hooks from 'eslint-plugin-react-hooks';
import refresh from 'eslint-plugin-react-refresh';
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'test-results/**', 'screenshots/**', 'playwright-report/**', 'src/plugins/**', 'src/router/**', '*.config.cjs'] },
  ...tseslint.configs.recommended,
  { files: ['**/*.{ts,tsx}'], plugins: { 'react-hooks': hooks, 'react-refresh': refresh }, rules: { ...hooks.configs.recommended.rules, 'react-hooks/set-state-in-effect': 'off', 'react-refresh/only-export-components': ['warn', { allowConstantExport: true }] } },
  { files: ['src/components/ui/*.tsx', 'src/components/Common.tsx'], rules: { 'react-refresh/only-export-components': 'off' } },
);
