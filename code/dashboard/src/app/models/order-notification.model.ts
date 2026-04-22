export type OrderStatus = 'CREATED' | 'PUBLISHED' | 'FAILED';

export interface OrderNotification {
  orderId:   string;
  status:    OrderStatus;
  amount:    number;
  currency:  string;
  createdAt: string;
  receivedAt?: Date;   // client-side timestamp when the SSE event arrived
}
