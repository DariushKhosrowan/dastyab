import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = Number(process.env.PORT || 3000);
const SITE_DIR = path.join(__dirname, 'site');

app.use(express.static(SITE_DIR));

app.get('/{*splat}', (_req, res) => {
  res.sendFile(path.join(SITE_DIR, 'index.html'));
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`DastYab site server listening on http://0.0.0.0:${PORT}`);
});
