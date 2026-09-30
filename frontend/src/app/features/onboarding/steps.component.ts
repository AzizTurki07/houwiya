import { Component, input } from '@angular/core';

const STEPS = ['Consent', 'Photo', 'Selfie', 'Review', 'Done'];

@Component({
  selector: 'app-steps',
  standalone: true,
  template: `
    <p class="visually-hidden">Step {{ current() }} of {{ steps.length }}: {{ steps[current() - 1] }}</p>
    <ol class="steps" aria-hidden="true">
      @for (step of steps; track step; let i = $index) {
        <li [class.done]="i < current()"></li>
      }
    </ol>
  `
})
export class StepsComponent {
  /** 1-based index of the active step. */
  readonly current = input.required<number>();
  readonly steps = STEPS;
}
