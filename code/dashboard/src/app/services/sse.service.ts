import { Injectable, NgZone, signal, inject } from '@angular/core';
import { Observable, timer }                  from 'rxjs';
import { retry }                              from 'rxjs/operators';
import { OrderNotification, OrderStatus }     from '../models/order-notification.model';

@Injectable({ providedIn: 'root' })
export class SseService {

  private readonly zone = inject(NgZone);

  readonly connected = signal(false);

  private readonly url = '/notifications/stream';

  // Throttle the health ping log — one line every 60 s max
  private lastPingLog = 0;

  connect(): Observable<OrderNotification> {
    return new Observable<OrderNotification>(observer => {
      const es = new EventSource(this.url);

      es.onopen = () => {
        // NgZone.run() ensures signal update triggers Angular change detection
        // even though EventSource callbacks fire outside Angular's zone.
        this.zone.run(() => {
          this.connected.set(true);
          console.log('[SSE] Connected to notification service');
        });
      };

      es.onerror = () => {
        this.zone.run(() => {
          this.connected.set(false);
          console.warn('[SSE] Connection lost — retrying in 3 s');
          observer.error('SSE connection lost');
          es.close();
        });
      };

      // Heartbeat comment (": keep-alive") arrives every 30 s.
      // addEventListener('message') won't fire for comments; listen on the raw
      // EventSource message handler to detect the keep-alive ping.
      es.addEventListener('message', () => {
        const now = Date.now();
        if (now - this.lastPingLog > 60_000) {
          console.log('[SSE] keep-alive ping received');
          this.lastPingLog = now;
        }
      });

      const statuses: OrderStatus[] = ['CREATED', 'PUBLISHED', 'FAILED'];
      statuses.forEach(status => {
        es.addEventListener(status, (ev: Event) => {
          const msg = ev as MessageEvent;
          try {
            const payload: OrderNotification = JSON.parse(msg.data);
            payload.receivedAt = new Date();
            console.log(`[SSE] Order event: ${payload.status} orderId=${payload.orderId}`);
            this.zone.run(() => observer.next(payload));
          } catch {
            console.warn('[SSE] Failed to parse event data', msg.data);
          }
        });
      });

      return () => {
        es.close();
        this.zone.run(() => this.connected.set(false));
      };

    }).pipe(
      retry({ delay: () => timer(3000) })
    );
  }
}
