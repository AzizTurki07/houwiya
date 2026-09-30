/** Backend rejects uploads over 10MB (spring.servlet.multipart.max-file-size); stay well under. */
const MAX_BYTES = 8 * 1024 * 1024;
/** Long edge after downscaling. Still plenty for MRZ/field OCR, which wants ~300dpi-equivalent text. */
const MAX_EDGE = 2560;

/**
 * Returns the image unchanged when it's already a reasonable upload, otherwise re-encodes it
 * as a downscaled JPEG. Downscaling only when needed matters: OCR accuracy drops with resolution.
 */
export async function prepareForUpload(image: Blob): Promise<Blob> {
  const acceptedType = image.type === 'image/jpeg' || image.type === 'image/png' || image.type === 'image/webp';
  if (image.size <= MAX_BYTES && acceptedType) {
    return image;
  }

  const bitmap = await createImageBitmap(image);
  const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  return canvasToJpeg(canvas);
}

export function canvasToJpeg(canvas: HTMLCanvasElement, quality = 0.92): Promise<Blob> {
  return new Promise((resolve, reject) =>
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('Could not encode image'))), 'image/jpeg', quality)
  );
}
