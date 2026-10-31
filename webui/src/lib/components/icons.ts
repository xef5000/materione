/**
 * The editor's one icon set: Lucide (ISC — see docs/architecture/web.md, "Icons").
 *
 * Every icon is a per-file import, so the bundle carries only the icons named
 * here, never the whole set and never a runtime CDN. To use a new icon, import
 * it below, add it to `icons`, and it becomes available as `<Icon name=… />`.
 * Keys are Lucide's own names, so the lucide.dev docs apply directly.
 */
import ArrowLeft from '@lucide/svelte/icons/arrow-left';
import ArrowLeftRight from '@lucide/svelte/icons/arrow-left-right';
import ArrowUp from '@lucide/svelte/icons/arrow-up';
import AppWindow from '@lucide/svelte/icons/app-window';
import Armchair from '@lucide/svelte/icons/armchair';
import Box from '@lucide/svelte/icons/box';
import Check from '@lucide/svelte/icons/check';
import ChefHat from '@lucide/svelte/icons/chef-hat';
import ChevronDown from '@lucide/svelte/icons/chevron-down';
import ChevronLeft from '@lucide/svelte/icons/chevron-left';
import ChevronRight from '@lucide/svelte/icons/chevron-right';
import ChevronUp from '@lucide/svelte/icons/chevron-up';
import Circle from '@lucide/svelte/icons/circle';
import Crop from '@lucide/svelte/icons/crop';
import Eraser from '@lucide/svelte/icons/eraser';
import File from '@lucide/svelte/icons/file';
import FileBraces from '@lucide/svelte/icons/file-braces';
import FileCode from '@lucide/svelte/icons/file-code';
import Folder from '@lucide/svelte/icons/folder';
import Frame from '@lucide/svelte/icons/frame';
import Image from '@lucide/svelte/icons/image';
import ImageOff from '@lucide/svelte/icons/image-off';
import Info from '@lucide/svelte/icons/info';
import Layers from '@lucide/svelte/icons/layers';
import LayoutGrid from '@lucide/svelte/icons/layout-grid';
import LayoutTemplate from '@lucide/svelte/icons/layout-template';
import List from '@lucide/svelte/icons/list';
import LoaderCircle from '@lucide/svelte/icons/loader-circle';
import PaintBucket from '@lucide/svelte/icons/paint-bucket';
import Package from '@lucide/svelte/icons/package';
import Pencil from '@lucide/svelte/icons/pencil';
import Pipette from '@lucide/svelte/icons/pipette';
import Plus from '@lucide/svelte/icons/plus';
import Redo2 from '@lucide/svelte/icons/redo-2';
import RefreshCw from '@lucide/svelte/icons/refresh-cw';
import Save from '@lucide/svelte/icons/save';
import Server from '@lucide/svelte/icons/server';
import Sparkles from '@lucide/svelte/icons/sparkles';
import Square from '@lucide/svelte/icons/square';
import Type from '@lucide/svelte/icons/type';
import Undo2 from '@lucide/svelte/icons/undo-2';
import Volume2 from '@lucide/svelte/icons/volume-2';
import X from '@lucide/svelte/icons/x';
import Zap from '@lucide/svelte/icons/zap';
import Coins from '@lucide/svelte/icons/coins';
import Database from '@lucide/svelte/icons/database';
import GitBranch from '@lucide/svelte/icons/git-branch';
import Plug from '@lucide/svelte/icons/plug';
import Workflow from '@lucide/svelte/icons/workflow';
import Activity from '@lucide/svelte/icons/activity';
import House from '@lucide/svelte/icons/house';
import TriangleAlert from '@lucide/svelte/icons/triangle-alert';
import Slash from '@lucide/svelte/icons/slash';
import Bold from '@lucide/svelte/icons/bold';
import Italic from '@lucide/svelte/icons/italic';
import Underline from '@lucide/svelte/icons/underline';
import Strikethrough from '@lucide/svelte/icons/strikethrough';
import Palette from '@lucide/svelte/icons/palette';
import Blend from '@lucide/svelte/icons/blend';
import RemoveFormatting from '@lucide/svelte/icons/remove-formatting';
import Ellipsis from '@lucide/svelte/icons/ellipsis';
import Shuffle from '@lucide/svelte/icons/shuffle';
import Upload from '@lucide/svelte/icons/upload';
import Sticker from '@lucide/svelte/icons/sticker';
import Minus from '@lucide/svelte/icons/minus';
import Lightbulb from '@lucide/svelte/icons/lightbulb';
import Shield from '@lucide/svelte/icons/shield';
import MoveHorizontal from '@lucide/svelte/icons/move-horizontal';
import Hammer from '@lucide/svelte/icons/hammer';
import RotateCw from '@lucide/svelte/icons/rotate-cw';
import Archive from '@lucide/svelte/icons/archive';
import Sprout from '@lucide/svelte/icons/sprout';
import Search from '@lucide/svelte/icons/search';
import Play from '@lucide/svelte/icons/play';
import Pause from '@lucide/svelte/icons/pause';
import Disc3 from '@lucide/svelte/icons/disc-3';
import User from '@lucide/svelte/icons/user';
import Crosshair from '@lucide/svelte/icons/crosshair';
import Ruler from '@lucide/svelte/icons/ruler';
import Grid3x3 from '@lucide/svelte/icons/grid-3x3';
import Flame from '@lucide/svelte/icons/flame';
import Timer from '@lucide/svelte/icons/timer';
import Trash2 from '@lucide/svelte/icons/trash-2';
import MousePointer2 from '@lucide/svelte/icons/mouse-pointer-2';
import Hand from '@lucide/svelte/icons/hand';
import ZoomIn from '@lucide/svelte/icons/zoom-in';
import ZoomOut from '@lucide/svelte/icons/zoom-out';
import Eye from '@lucide/svelte/icons/eye';
import EyeOff from '@lucide/svelte/icons/eye-off';
import Lock from '@lucide/svelte/icons/lock';
import LockOpen from '@lucide/svelte/icons/lock-open';
import Copy from '@lucide/svelte/icons/copy';
import GripVertical from '@lucide/svelte/icons/grip-vertical';
import Magnet from '@lucide/svelte/icons/magnet';
import Scan from '@lucide/svelte/icons/scan';
import RectangleHorizontal from '@lucide/svelte/icons/rectangle-horizontal';
import SquareDashed from '@lucide/svelte/icons/square-dashed';

export const icons = {
  'arrow-left': ArrowLeft,
  'arrow-left-right': ArrowLeftRight,
  'app-window': AppWindow,
  'arrow-up': ArrowUp,
  activity: Activity,
  armchair: Armchair,
  box: Box,
  check: Check,
  'chef-hat': ChefHat,
  'chevron-down': ChevronDown,
  'chevron-left': ChevronLeft,
  'chevron-right': ChevronRight,
  'chevron-up': ChevronUp,
  circle: Circle,
  crop: Crop,
  eraser: Eraser,
  file: File,
  'file-braces': FileBraces,
  'file-code': FileCode,
  folder: Folder,
  frame: Frame,
  house: House,
  image: Image,
  'image-off': ImageOff,
  info: Info,
  layers: Layers,
  'layout-grid': LayoutGrid,
  'layout-template': LayoutTemplate,
  list: List,
  'loader-circle': LoaderCircle,
  package: Package,
  'paint-bucket': PaintBucket,
  pencil: Pencil,
  pipette: Pipette,
  plus: Plus,
  'redo-2': Redo2,
  'refresh-cw': RefreshCw,
  save: Save,
  server: Server,
  slash: Slash,
  sparkles: Sparkles,
  square: Square,
  type: Type,
  'triangle-alert': TriangleAlert,
  'undo-2': Undo2,
  'volume-2': Volume2,
  x: X,
  zap: Zap,
  coins: Coins,
  database: Database,
  'git-branch': GitBranch,
  plug: Plug,
  workflow: Workflow,
  blend: Blend,
  bold: Bold,
  italic: Italic,
  minus: Minus,
  palette: Palette,
  'remove-formatting': RemoveFormatting,
  shuffle: Shuffle,
  ellipsis: Ellipsis,
  upload: Upload,
  sticker: Sticker,
  strikethrough: Strikethrough,
  underline: Underline,
  lightbulb: Lightbulb,
  shield: Shield,
  'move-horizontal': MoveHorizontal,
  hammer: Hammer,
  'rotate-cw': RotateCw,
  archive: Archive,
  sprout: Sprout,
  search: Search,
  play: Play,
  pause: Pause,
  'disc-3': Disc3,
  user: User,
  crosshair: Crosshair,
  ruler: Ruler,
  'grid-3x3': Grid3x3,
  flame: Flame,
  timer: Timer,
  'trash-2': Trash2,
  'mouse-pointer-2': MousePointer2,
  'hand': Hand,
  'zoom-in': ZoomIn,
  'zoom-out': ZoomOut,
  'eye': Eye,
  'eye-off': EyeOff,
  'lock': Lock,
  'lock-open': LockOpen,
  'copy': Copy,
  'grip-vertical': GripVertical,
  'magnet': Magnet,
  'scan': Scan,
  'rectangle-horizontal': RectangleHorizontal,
  'square-dashed': SquareDashed,
} as const;

export type IconName = keyof typeof icons;

/** Each content type's icon, used in its tab, Start tile and empty states. */
export const CONTENT_TYPE_ICONS: Record<string, IconName> = {
  items: 'package',
  blocks: 'box',
  furnitures: 'armchair',
  recipes: 'chef-hat',
  templates: 'layout-template',
  glyphs: 'type',
  sounds: 'volume-2',
  paintings: 'frame',
  effects: 'sparkles',
  guis: 'app-window',
};

export function contentTypeIcon(directory: string): IconName {
  return CONTENT_TYPE_ICONS[directory] ?? 'file';
}
