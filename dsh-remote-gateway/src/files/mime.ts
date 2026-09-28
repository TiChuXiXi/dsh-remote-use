/**
 * 扩展名 → MIME 映射。
 *
 * 刻意不引入 mime 类依赖：本插件要服务的主要是 dsh 自己产出的成品
 * （APK / PDF / docx / 图片 / 压缩包 / 文本），覆盖这些即可。
 * 未知扩展名一律 `application/octet-stream` —— 绝不用启发式猜，
 * 猜错会让 Content-Type 变成 XSS 载体（例如把 .html 当 text/plain 或反之）。
 */
import { extname } from 'node:path'

const TYPES: Record<string, string> = {
  // 文档
  '.pdf': 'application/pdf',
  '.doc': 'application/msword',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.xls': 'application/vnd.ms-excel',
  '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  '.ppt': 'application/vnd.ms-powerpoint',
  '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  '.odt': 'application/vnd.oasis.opendocument.text',
  '.ods': 'application/vnd.oasis.opendocument.spreadsheet',
  '.md': 'text/markdown; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.log': 'text/plain; charset=utf-8',
  '.csv': 'text/csv; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.yaml': 'application/yaml; charset=utf-8',
  '.yml': 'application/yaml; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.ts': 'text/plain; charset=utf-8',

  // Android / 可执行
  '.apk': 'application/vnd.android.package-archive',
  '.aab': 'application/octet-stream',
  '.jar': 'application/java-archive',
  '.dex': 'application/octet-stream',
  '.so': 'application/octet-stream',
  '.exe': 'application/vnd.microsoft.portable-executable',
  '.msi': 'application/x-msi',
  '.dmg': 'application/x-apple-diskimage',
  '.deb': 'application/vnd.debian.binary-package',
  '.rpm': 'application/x-rpm',
  '.sh': 'text/x-shellscript; charset=utf-8',

  // 图片
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.bmp': 'image/bmp',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.heic': 'image/heic',
  '.avif': 'image/avif',

  // 音视频
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.ogg': 'audio/ogg',
  '.m4a': 'audio/mp4',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mov': 'video/quicktime',
  '.mkv': 'video/x-matroska',

  // 压缩包
  '.zip': 'application/zip',
  '.gz': 'application/gzip',
  '.tgz': 'application/gzip',
  '.bz2': 'application/x-bzip2',
  '.xz': 'application/x-xz',
  '.zst': 'application/zstd',
  '.7z': 'application/x-7z-compressed',
  '.rar': 'application/vnd.rar',
  '.tar': 'application/x-tar',

  // 字体与其它
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.wasm': 'application/wasm',
  '.onnx': 'application/octet-stream',
  '.bin': 'application/octet-stream',
  '.db': 'application/vnd.sqlite3',
  '.sqlite': 'application/vnd.sqlite3',
}

/** 默认类型。 */
export const DEFAULT_MIME = 'application/octet-stream'

/** 按文件名推断 Content-Type。 */
export function mimeFor(name: string): string {
  const ext = extname(name).toLowerCase()
  if (ext.length === 0) return DEFAULT_MIME
  return TYPES[ext] ?? DEFAULT_MIME
}

/**
 * 该类型是否应该以附件形式下载。
 *
 * HTML/SVG/JS 这类能被浏览器当作活动内容执行的东西一律强制附件 ——
 * 否则一个被写进工作区的恶意 HTML 就能在同源下拿到网关的会话 cookie。
 * 注意网关会话 cookie 是 HttpOnly，读不到，但同源脚本仍可代用户发起请求，
 * 所以这层防护是必要的。
 */
export function shouldForceAttachment(mime: string): boolean {
  return (
    mime.startsWith('text/html') ||
    mime.startsWith('image/svg') ||
    mime.startsWith('text/javascript') ||
    mime.startsWith('application/xml') ||
    mime.startsWith('text/xml')
  )
}

/** 按 RFC 5987 生成 Content-Disposition，兼顾 ASCII 与 UTF-8 文件名。 */
export function contentDisposition(name: string, forceAttachment: boolean): string {
  const kind = forceAttachment ? 'attachment' : 'attachment'
  // ASCII 兜底：非 ASCII 字符替换为下划线，避免头部出现裸 UTF-8。
  const ascii = name.replace(/[^\x20-\x7e]/gu, '_').replaceAll('"', '') || 'download'
  const encoded = encodeURIComponent(name)
  return `${kind}; filename="${ascii}"; filename*=UTF-8''${encoded}`
}
