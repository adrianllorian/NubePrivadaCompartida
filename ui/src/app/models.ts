export interface User {
  username: string;
  role: string;
}

export interface SetupStatus {
  required: boolean;
}

export interface Item {
  id: string;
  name: string;
  folder: boolean;
  size: number;
  owner: string;
  createdAt: string;
  contentType?: string;
  shareToken?: string;
  path?: string | null;
}

export interface Stats {
  total: number;
  used: number;
  free: number;
  appUsed: number;
  fileCount: number;
}

export interface ActivityEntry {
  id: number;
  username: string;
  action: string;
  detail: string;
  ip: string;
  createdAt: string;
}

export interface ActivityPage {
  content: ActivityEntry[];
  total: number;
  page: number;
  size: number;
  pages: number;
  actions: string[];
}

export interface Listing {
  parentId: string | null;
  breadcrumb: Item[];
  items: Item[];
  search: boolean;
}

export interface FolderDto {
  id: string;
  name: string;
  path: string;
}

export interface UploadInit {
  uploadId: string;
  received: number;
}

export interface UploadChunk {
  received: number;
}
