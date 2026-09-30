import { Component, ElementRef, OnDestroy, OnInit, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { DocumentResponse } from '../../../core/models/onboarding.model';
import { OnboardingService } from '../../../core/services/onboarding.service';
import { errorMessage } from '../../../core/utils/http-error';
import { canvasToJpeg } from '../../../core/utils/image';
import { StepsComponent } from '../steps.component';

type Mode = 'intro' | 'camera' | 'uploading' | 'failed';

/**
 * The three poses the AI service's liveness check expects, in order: frontal first (it is
 * also the one compared with the document portrait), then a turn each way.
 */
export const POSES = [
  'Look straight at the camera',
  'Slowly turn your head to one side',
  'Now turn your head to the other side'
];

/** Seconds of countdown before each frame: time to get into the pose, hands-free. */
const COUNTDOWN = 3;
/** Faces are compared at ~112px; 1280 on the long edge is plenty and keeps uploads small. */
const MAX_EDGE = 1280;

@Component({
  selector: 'app-selfie',
  standalone: true,
  imports: [RouterLink, StepsComponent],
  templateUrl: './selfie.component.html',
  styleUrl: './selfie.component.scss'
})
export class SelfieComponent implements OnInit, OnDestroy {
  private readonly onboarding = inject(OnboardingService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly sessionId = this.route.snapshot.paramMap.get('id')!;

  private readonly video = viewChild<ElementRef<HTMLVideoElement>>('video');
  private stream: MediaStream | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private uploadSub: Subscription | null = null;
  private frames: Blob[] = [];

  readonly poses = POSES;
  readonly ready = signal(false);
  readonly mode = signal<Mode>('intro');
  /** Index into POSES while capturing. */
  readonly pose = signal(0);
  readonly countdown = signal(0);
  readonly error = signal<string | null>(null);
  readonly loadError = signal<string | null>(null);
  readonly cameraSupported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;

  ngOnInit(): void {
    this.onboarding.getDocument(this.sessionId).subscribe({
      next: (doc) => {
        if (doc.confirmedAt) {
          this.router.navigate(['/onboarding', this.sessionId, 'result'], { replaceUrl: true });
        } else if (doc.backSideRequired && !doc.backSideCaptured) {
          this.router.navigate(['/onboarding', this.sessionId, 'capture-back'], { replaceUrl: true });
        } else {
          this.ready.set(true);
        }
      },
      error: (err) => {
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.router.navigate(['/onboarding', this.sessionId, 'capture'], { replaceUrl: true });
        } else {
          this.loadError.set(errorMessage(err, "Couldn't load this verification."));
        }
      }
    });
  }

  ngOnDestroy(): void {
    this.clearTimer();
    this.stopCamera();
    this.uploadSub?.unsubscribe();
  }

  async start(): Promise<void> {
    this.error.set(null);
    this.frames = [];
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } }
      });
    } catch (err) {
      this.stopCamera();
      this.error.set(
        err instanceof DOMException && err.name === 'NotAllowedError'
          ? 'Camera access was blocked. Allow it in your browser or app settings, then try again.'
          : "Couldn't open the front camera."
      );
      return;
    }
    this.mode.set('camera');
    // Wait a tick for the <video> element to render before attaching the stream.
    setTimeout(() => {
      const el = this.video()?.nativeElement;
      if (el && this.stream) {
        el.srcObject = this.stream;
        el.play().catch(() => undefined);
      }
      this.runPose(0);
    });
  }

  cancel(): void {
    this.clearTimer();
    this.stopCamera();
    this.frames = [];
    this.mode.set('intro');
  }

  /** The selfie didn't pass: go on anyway -- a reviewer compares the faces instead. */
  continueToReview(): void {
    this.router.navigate(['/onboarding', this.sessionId, 'review']);
  }

  private runPose(index: number): void {
    this.pose.set(index);
    this.countdown.set(COUNTDOWN);
    const tick = () => {
      if (this.countdown() > 1) {
        this.countdown.update((c) => c - 1);
        this.timer = setTimeout(tick, 1000);
      } else {
        this.countdown.set(0);
        this.captureFrame(index);
      }
    };
    this.timer = setTimeout(tick, 1000);
  }

  private async captureFrame(index: number): Promise<void> {
    const el = this.video()?.nativeElement;
    if (!el || !el.videoWidth) {
      this.cancel();
      this.error.set("The camera didn't start. Please try again.");
      return;
    }
    // The raw (unmirrored) frame: the preview is mirrored only so moving feels natural.
    const scale = Math.min(1, MAX_EDGE / Math.max(el.videoWidth, el.videoHeight));
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(el.videoWidth * scale);
    canvas.height = Math.round(el.videoHeight * scale);
    canvas.getContext('2d')!.drawImage(el, 0, 0, canvas.width, canvas.height);
    this.frames.push(await canvasToJpeg(canvas, 0.9));

    if (index + 1 < POSES.length) {
      this.runPose(index + 1);
    } else {
      this.stopCamera();
      this.upload();
    }
  }

  private upload(): void {
    this.mode.set('uploading');
    this.uploadSub = this.onboarding.submitSelfie(this.sessionId, this.frames).subscribe({
      next: (doc) => {
        this.frames = [];
        if (passed(doc)) {
          this.router.navigate(['/onboarding', this.sessionId, 'review']);
        } else {
          this.error.set(failureMessage(doc));
          this.mode.set('failed');
        }
      },
      error: (err) => {
        this.frames = [];
        const status = err instanceof HttpErrorResponse ? err.status : -1;
        this.error.set(
          status === 502 || status === 503 || status === 504 || status === 0
            ? 'Face checking is temporarily unavailable. Please try again in a moment.'
            : errorMessage(err, "Couldn't check your selfie. Please try again.")
        );
        this.mode.set('intro');
      }
    });
  }

  private clearTimer(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private stopCamera(): void {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
  }
}

function passed(doc: DocumentResponse): boolean {
  return doc.faceMatched === true && doc.livenessPassed === true;
}

/** What to tell the applicant: the outcome and what to change, never a score. */
function failureMessage(doc: DocumentResponse): string {
  if (doc.faceMatched === null) {
    return "We couldn't find a clear face, either in the selfie or on your document's photo. Face the camera in good light.";
  }
  if (!doc.faceMatched) {
    return "We couldn't match your selfie to the photo on your document. Remove glasses or a hat, face the light, and try again.";
  }
  return "We couldn't confirm the selfie was taken live. Follow each instruction: look straight, then turn your head one way, then the other.";
}
