/**
 * Applies the stored theme before first paint.
 *
 * This has to be a blocking inline script: if the theme were applied after
 * hydration, a user who chose dark would see a white flash on every navigation.
 * Reads are wrapped because localStorage throws in some privacy modes, and the
 * page must still render with the OS preference if it does.
 */
const SCRIPT = `
try {
  var stored = localStorage.getItem('trackline-theme');
  if (stored === 'light' || stored === 'dark') {
    document.documentElement.dataset.theme = stored;
  }
} catch (error) {}
`;

export function ThemeScript() {
  return <script suppressHydrationWarning dangerouslySetInnerHTML={{ __html: SCRIPT }} />;
}
