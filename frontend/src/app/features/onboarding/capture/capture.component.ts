import { Component, ElementRef, OnDestroy, OnInit, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { DocumentResponse, DocumentSide, DocumentType } from '../../../core/models/onboarding.model';
import { NativeCameraService } from '../../../core/services/native-camera.service';
import { OnboardingService } from '../../../core/services/onboarding.service';
import { errorMessage } from '../../../core/utils/http-error';
import { canvasToJpeg, prepareForUpload } from '../../../core/utils/image';
import { DOCUMENT_LABEL } from '../document-fields';
import { routeForSession } from '../session-routing';
import { StepsComponent } from '../steps.component';

type Mode = 'choose' | 'camera' | 'preview' | 'uploading' | 'processing';

interface UploadError {
  /** unreadable = AI couldn't find the MRZ/card (422) -> retake; unavailable = try the same photo again. */
  kind: 'unreadable' | 'unavailable' | 'other';
  message: string;
}

@Component({
  selector: 'app-capture',
  standalone: true,
  imports: [RouterLink, StepsComponent],
  templateUrl: './capture.component.html',
  styleUrl: './capture.component.scss'
})
export class CaptureComponent implements OnInit, OnDestroy {
  private readonly onboarding = inject(OnboardingService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly sessionId = this.route.snapshot.paramMap.get('id')!;

  private readonly video = viewChild<ElementRef<HTMLVideoElement>>('video');
  private stream: MediaStream | null = null;
  private uploadSub: Subscription | null = null;

  /** FRONT, or BACK on the capture-back route (CIN only). */
  readonly side: DocumentSide = this.route.snapshot.data['side'] === 'BACK' ? 'BACK' : 'FRONT';
  readonly labels = DOCUMENT_LABEL;
  readonly documentTypes: DocumentType[] = ['PASSPORT', 'CIN'];
  readonly documentType = signal<DocumentType | null>(null);
  readonly mode = signal<Mode>('choose');
  readonly ready = signal(false);
  readonly image = signal<Blob | null>(null);
  readonly previewUrl = signal<string | null>(null);
  readonly progress = signal(0);
  readonly error = signal<UploadError | null>(null);
  readonly cameraError = signal<string | null>(null);
  readonly cameraSupported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;
  private readonly nativeCamera = inject(NativeCameraService);
  /** Running as the Capacitor app: use the phone's camera/gallery instead of the web viewfinder. */
  readonly native = this.nativeCamera.isNative;

  ngOnInit(): void {
    const preselected = this.side === 'BACK' ? 'CIN' : this.route.snapshot.queryParamMap.get('type');
    if (preselected === 'PASSPORT' || preselected === 'CIN') {
      this.documentType.set(preselected);
    }

    this.onboarding.getSession(this.sessionId).subscribe({
      next: (session) => {
        // Upload is allowed after consent, and again from review (retake) until the user confirms.
        // The back needs the front first, which moves the session to PENDING_REVIEW.
        const allowed = this.side === 'BACK'
          ? session.status === 'PENDING_REVIEW'
          : session.status === 'CONSENT_GIVEN' || session.status === 'PENDING_REVIEW';
        if (allowed) {
          this.ready.set(true);
        } else {
          this.router.navigate(routeForSession(session.id, session.status), { replaceUrl: true });
        }
      },
      error: (err) => this.error.set({ kind: 'other', message: errorMessage(err, "Couldn't load this verification.") })
    });
  }

  ngOnDestroy(): void {
    this.stopCamera();
    this.uploadSub?.unsubscribe();
    this.revokePreview();
  }

  selectType(type: DocumentType): void {
    this.documentType.set(type);
    this.error.set(null);
  }

  async takeNative(source: 'camera' | 'gallery'): Promise<void> {
    this.cameraError.set(null);
    this.error.set(null);
    try {
      const photo = await this.nativeCamera.take(source);
      if (photo) {
        this.setImage(photo);
      }
    } catch (err) {
      this.cameraError.set(err instanceof Error ? err.message : "Couldn't open the camera.");
    }
  }

  async startCamera(): Promise<void> {
    this.cameraError.set(null);
    this.error.set(null);
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          facingMode: { ideal: 'environment' },
          // Ask for a high resolution: small text (MRZ, card fields) needs the pixels.
          width: { ideal: 1920 },
          height: { ideal: 1080 }
        }
      });
      this.mode.set('camera');
      // Wait a tick for the <video> element to render before attaching the stream.
      setTimeout(() => {
        const el = this.video()?.nativeElement;
        if (el && this.stream) {
          el.srcObject = this.stream;
          el.play().catch(() => undefined);
        }
      });
    } catch (err) {
      this.stopCamera();
      this.cameraError.set(
        err instanceof DOMException && err.name === 'NotAllowedError'
          ? 'Camera access was blocked. Allow it in your browser settings, or upload a photo instead.'
          : "Couldn't open the camera. You can upload a photo instead."
      );
    }
  }

  async takePhoto(): Promise<void> {
    const el = this.video()?.nativeElement;
    if (!el || !el.videoWidth) {
      return;
    }
    // Keep the full frame: the AI service finds the document itself, and cropping to the
    // on-screen guide risks cutting off an edge the detector needs.
    const canvas = document.createElement('canvas');
    canvas.width = el.videoWidth;
    canvas.height = el.videoHeight;
    canvas.getContext('2d')!.drawImage(el, 0, 0);
    const blob = await canvasToJpeg(canvas);
    this.stopCamera();
    this.setImage(blob);
  }

  cancelCamera(): void {
    this.stopCamera();
    this.mode.set('choose');
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = ''; // allow picking the same file again after a failed attempt
    if (!file) {
      return;
    }
    if (!file.type.startsWith('image/')) {
      this.error.set({ kind: 'other', message: 'Please choose an image file (JPEG or PNG).' });
      return;
    }
    this.error.set(null);
    this.setImage(file);
  }

  retake(): void {
    this.revokePreview();
    this.image.set(null);
    this.mode.set('choose');
  }

  async submit(): Promise<void> {
    const type = this.documentType();
    const original = this.image();
    if (!type || !original) {
      return;
    }
    this.error.set(null);
    this.progress.set(0);
    this.mode.set('uploading');

    let upload: Blob;
    try {
      upload = await prepareForUpload(original);
    } catch {
      this.mode.set('preview');
      this.error.set({ kind: 'other', message: "Couldn't process this image. Try another photo." });
      return;
    }

    const filename = upload.type === 'image/png' ? 'document.png' : 'document.jpg';
    this.uploadSub = this.onboarding.uploadDocument(this.sessionId, type, upload, filename, this.side).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          const pct = Math.round((event.loaded / event.total) * 100);
          this.progress.set(pct);
          if (pct >= 100) {
            this.mode.set('processing');
          }
        } else if (event.type === HttpEventType.Response) {
          this.router.navigate(['/onboarding', this.sessionId, this.nextStep(event.body)]);
        }
      },
      error: (err) => {
        this.mode.set('preview');
        this.error.set(this.toUploadError(err));
      }
    });
  }

  /** After a CIN front comes its back (unless already captured), then the selfie, then review. */
  private nextStep(doc: DocumentResponse | null): string {
    if (doc?.backSideRequired && !doc.backSideCaptured) {
      return 'capture-back';
    }
    return doc?.selfieRequired && !doc.selfieCaptured ? 'selfie' : 'review';
  }

  private toUploadError(err: unknown): UploadError {
    const status = err instanceof HttpErrorResponse ? err.status : -1;
    if (status === 422) {
      return { kind: 'unreadable', message: errorMessage(err, "We couldn't read your document from this photo.") };
    }
    if (status === 502 || status === 503 || status === 504 || status === 0) {
      return {
        kind: 'unavailable',
        message: status === 0
          ? "Can't reach the server. Check your connection and try again."
          : 'Document reading is temporarily unavailable. Please try again in a moment.'
      };
    }
    if (status === 413) {
      return { kind: 'other', message: 'This photo is too large. Try a smaller one.' };
    }
    return { kind: 'other', message: errorMessage(err) };
  }

  private setImage(blob: Blob): void {
    this.revokePreview();
    this.image.set(blob);
    this.previewUrl.set(URL.createObjectURL(blob));
    this.mode.set('preview');
  }

  private revokePreview(): void {
    const url = this.previewUrl();
    if (url) {
      URL.revokeObjectURL(url);
      this.previewUrl.set(null);
    }
  }

  private stopCamera(): void {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
  }
}
