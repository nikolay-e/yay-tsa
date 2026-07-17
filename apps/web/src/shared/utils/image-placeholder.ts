export function getImagePlaceholder() {
  const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96">' +
    '<rect fill="#252525" width="96" height="96"/>' +
    '<g fill="#3d3d3d"><path d="M43 66V36l24-6v30h-4V35l-16 4v27z"/>' +
    '<circle cx="39" cy="66" r="7"/><circle cx="63" cy="60" r="7"/></g>' +
    '</svg>';
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}
