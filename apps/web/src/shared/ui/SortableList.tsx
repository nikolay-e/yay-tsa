import { type ReactNode, useCallback } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
  rectSortingStrategy,
  useSortable,
  arrayMove,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical } from 'lucide-react';
import { cn } from '@/shared/utils/cn';

interface SortableListProps<T extends { Id: string }> {
  items: T[];
  onReorder: (reorderedItems: T[]) => void;
  renderItem: (item: T, index: number) => ReactNode;
  layout?: 'vertical' | 'grid';
  gridClassName?: string;
}

function SortableItem({
  id,
  children,
  layout,
}: {
  id: string;
  children: ReactNode;
  layout: 'vertical' | 'grid';
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
  });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn('group/sortable relative', isDragging && 'z-50 opacity-75')}
      {...attributes}
    >
      <button
        type="button"
        className={cn(
          'absolute z-10 cursor-grab touch-none active:cursor-grabbing',
          'text-text-tertiary hover:text-text-secondary',
          'rounded-sm p-1 transition-opacity',
          'opacity-0 group-hover/sortable:opacity-100 focus:opacity-100',
          layout === 'vertical' ? 'top-1/2 left-1 -translate-y-1/2' : 'top-1 left-1'
        )}
        {...listeners}
        aria-label="Drag to reorder"
      >
        <GripVertical className="h-4 w-4" />
      </button>
      <div className={layout === 'vertical' ? 'pl-7' : ''}>{children}</div>
    </div>
  );
}

export function SortableList<T extends { Id: string }>({
  items,
  onReorder,
  renderItem,
  layout = 'vertical',
  gridClassName,
}: SortableListProps<T>) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id) return;

      const oldIndex = items.findIndex(item => item.Id === active.id);
      const newIndex = items.findIndex(item => item.Id === over.id);
      if (oldIndex === -1 || newIndex === -1) return;

      onReorder(arrayMove(items, oldIndex, newIndex));
    },
    [items, onReorder]
  );

  const strategy = layout === 'grid' ? rectSortingStrategy : verticalListSortingStrategy;

  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
      <SortableContext items={items.map(item => item.Id)} strategy={strategy}>
        <div className={layout === 'grid' ? gridClassName : 'space-y-1'}>
          {items.map((item, index) => (
            <SortableItem key={item.Id} id={item.Id} layout={layout}>
              {renderItem(item, index)}
            </SortableItem>
          ))}
        </div>
      </SortableContext>
    </DndContext>
  );
}
