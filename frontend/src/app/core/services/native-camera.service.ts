import { Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';

/**
 * The phone's own camera and gallery when running as the Capacitor app. The browser build
 * keeps its getUserMedia viewfinder (CaptureComponent); this is only used when isNative.
 */
@Injectable({ providedIn: 'root' })
export class NativeCameraService {
  readonly isNative = Capacitor.isNativePlatform();

  /**
   * Opens the native camera ('camera') or photo picker ('gallery').
   * Resolves to the photo, or null if the user backed out; rejects with a user-facing
   * message if permission was refused or the camera failed.
   */
  async take(source: 'camera' | 'gallery'): Promise<Blob | null> {
    let webPath: string | undefined;
    try {
      const photo = await Camera.getPhoto({
        source: source === 'camera' ? CameraSource.Camera : CameraSource.Photos,
        resultType: CameraResultType.Uri,
        // Text recognition needs the pixels: keep quality high, cap the long edge at 2560
        // (same as the web upload), and let the plugin fix EXIF rotation.
        quality: 92,
        width: 2560,
        correctOrientation: true,
        saveToGallery: false
      });
      webPath = photo.webPath;
    } catch (err) {
      const message = String((err as { message?: string })?.message ?? err).toLowerCase();
      if (message.includes('cancel')) {
        return null;
      }
      if (message.includes('denied') || message.includes('permission')) {
        throw new Error(
          source === 'camera'
            ? 'Camera access was refused. Allow it in your phone settings (Apps → Houwiya → Permissions), or upload a photo instead.'
            : 'Photo access was refused. Allow it in your phone settings, or take a photo instead.'
        );
      }
      throw new Error("Couldn't open the camera. Please try again.");
    }
    if (!webPath) {
      return null;
    }
    const response = await fetch(webPath);
    return response.blob();
  }
}
