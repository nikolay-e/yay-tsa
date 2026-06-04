import { useEffect, type ReactNode } from 'react';
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

// Class applied to <body> only while a drag is in flight. Suppresses the native
// text-selection a drag gesture would otherwise trigger across the whole document
// (the pointer can travel outside the dragged row) and shows a grabbing cursor.
// Always paired with a removal on drag end/cancel/unmount so it never sticks.
const BODY_DRAGGING_CLASS = 'dragging';

function setBodyDragging(active: boolean) {
  if (typeof document === 'undefined') return;
  document.body.classList.toggle(BODY_DRAGGING_CLASS, active);
}

type SortableListProps<T extends { Id: string }> = Readonly<{
  items: T[];
  onReorder: (reorderedItems: T[]) => void;
  renderItem: (item: T, index: number) => ReactNode;
  layout?: 'vertical' | 'grid';
  gridClassName?: string;
}>;

function SortableItem({
  id,
  children,
  layout,
}: Readonly<{
  id: string;
  children: ReactNode;
  layout: 'vertical' | 'grid';
}>) {
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
      className={cn('group/sortable relative select-none', isDragging && 'z-50 opacity-75')}
      {...attributes}
    >
      <button
        type="button"
        className={cn(
          'absolute z-10 cursor-grab touch-none active:cursor-grabbing',
          'text-text-secondary hover:text-text-primary',
          'rounded-sm p-1 transition-opacity',
          'opacity-50 group-hover/sortable:opacity-100 focus:opacity-100',
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

  // Safety net: if the component unmounts mid-drag (e.g. the user switches the sort
  // mode away from custom order), drop the body class so selection never stays disabled.
  useEffect(() => () => setBodyDragging(false), []);

  const handleDragEnd = (event: DragEndEvent) => {
    setBodyDragging(false);
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    const oldIndex = items.findIndex(item => item.Id === active.id);
    const newIndex = items.findIndex(item => item.Id === over.id);
    if (oldIndex === -1 || newIndex === -1) return;

    onReorder(arrayMove(items, oldIndex, newIndex));
  };

  const strategy = layout === 'grid' ? rectSortingStrategy : verticalListSortingStrategy;

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={() => setBodyDragging(true)}
      onDragEnd={handleDragEnd}
      onDragCancel={() => setBodyDragging(false)}
    >
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
