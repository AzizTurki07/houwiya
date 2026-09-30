import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([{ path: 'login', children: [] }]), provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
  });

  afterEach(() => localStorage.clear());

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('hides the top bar when signed out', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.topbar')).toBeNull();
  });

  it('shows the top bar and signs out', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.componentInstance.auth.isAuthenticated.set(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.topbar')).not.toBeNull();

    fixture.componentInstance.logout();
    fixture.detectChanges();
    expect(fixture.componentInstance.auth.isAuthenticated()).toBeFalse();
    expect(fixture.nativeElement.querySelector('.topbar')).toBeNull();
  });
});
