import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe }  from '@angular/common';
import { Subscription }                          from 'rxjs';
import { bufferTime, filter }                    from 'rxjs/operators';
import { MatTableModule }                        from '@angular/material/table';
import { MatCardModule }                         from '@angular/material/card';
import { MatChipsModule }                        from '@angular/material/chips';
import { MatIconModule }                         from '@angular/material/icon';
import { MatBadgeModule }                        from '@angular/material/badge';
import { MatDividerModule }                      from '@angular/material/divider';
import { MatTooltipModule }                      from '@angular/material/tooltip';
import { SseService }                            from '../../services/sse.service';
import { OrderNotification }                     from '../../models/order-notification.model';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatTableModule, MatCardModule, MatChipsModule,
    MatIconModule, MatBadgeModule, MatDividerModule, MatTooltipModule
  ],
  templateUrl: './dashboard.component.html',
  styleUrl:    './dashboard.component.scss'
})
export class DashboardComponent implements OnInit, OnDestroy {

  readonly sseService = inject(SseService);

  orders          = signal<OrderNotification[]>([]);
  totalReceived   = signal(0);
  createdCount    = signal(0);
  failedCount     = signal(0);

  columns = ['orderId', 'status', 'amount', 'currency', 'receivedAt'];

  private subscription?: Subscription;
  private readonly MAX_DISPLAY = 100;

  ngOnInit(): void {
    this.subscription = this.sseService.connect().pipe(
      bufferTime(16),
      filter((batch: OrderNotification[]) => batch.length > 0)
    ).subscribe((batch: OrderNotification[]) => {
      this.totalReceived.update(n => n + batch.length);
      this.createdCount.update(n => n + batch.filter(o => o.status === 'CREATED').length);
      this.failedCount.update(n  => n + batch.filter(o => o.status === 'FAILED').length);
      this.orders.update(current =>
        [...batch.reverse(), ...current].slice(0, this.MAX_DISPLAY)
      );
    });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  chipClass(status: string): string {
    const map: Record<string, string> = {
      CREATED:   'chip-created',
      PUBLISHED: 'chip-published',
      FAILED:    'chip-failed',
    };
    return map[status] ?? 'chip-created';
  }
}
