export interface CoreBand { id: string; label: string; n: number; color: string; density?: number }
export function createCore(options: { w: number; h: number; dpr?: number; params?: Record<string, number>; bands?: CoreBand[]; outerN?: number }): { render(context: CanvasRenderingContext2D, time: number): void };
