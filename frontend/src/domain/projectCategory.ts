export const PROJECT_CATEGORIES = [
  { id: 'SMALL_APPLIANCE', label: '小家电', weight: 4 },
  { id: 'LARGE_APPLIANCE', label: '大家电', weight: 4 },
  { id: 'OTHER', label: '其他', weight: 2 },
] as const
export type ProjectCategory = typeof PROJECT_CATEGORIES[number]['id']
export function isProjectCategory(value: unknown): value is ProjectCategory {
  return PROJECT_CATEGORIES.some(category => category.id === value)
}
export function projectCategoryLabel(value?: ProjectCategory): string {
  return PROJECT_CATEGORIES.find(category => category.id === (value ?? 'OTHER'))!.label
}
