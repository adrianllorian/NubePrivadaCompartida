import { Component, ChangeDetectorRef, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';
import { ActivityPage, FolderDto, Item, Listing, Stats, User } from './models';

type SortKey = 'name' | 'size' | 'date' | 'owner';
type PreviewKind = 'image' | 'video' | 'audio' | 'pdf' | 'text' | 'other';

interface UploadEntry {
  file: File | null;
  folders: string[];
}

@Component({
  selector: 'app-root',
  imports: [CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App implements OnInit {
  private api = inject(ApiService);
  private cdr = inject(ChangeDetectorRef);

  user: User | null = null;
  loading = true;

  loginUser = '';
  loginPass = '';
  loginError = '';
  loginBusy = false;

  setupRequired = false;
  setupUser = '';
  setupPass = '';
  setupPassConfirm = '';
  setupError = '';
  setupBusy = false;

  listing: Listing = { parentId: null, breadcrumb: [], items: [], search: false };
  stats: Stats | null = null;
  users: User[] = [];

  tab: 'files' | 'activity' | 'users' = 'files';
  notice = '';

  query = '';
  sortKey: SortKey = 'name';
  sortDir = 1;

  dragging = false;
  uploading = false;
  uploadPercent = 0;
  uploadNames = '';
  uploadText = '';
  private cancelRequested = false;
  private currentUploadId: string | null = null;

  selected = new Set<string>();

  showFolderDialog = false;
  newFolderName = '';

  renameItem: Item | null = null;
  renameValue = '';

  moveItem: Item | null = null;
  moveTarget: string | null = null;
  folders: FolderDto[] = [];

  preview: Item | null = null;
  previewText = '';
  previewLoading = false;

  thumbFailed = new Set<string>();

  activity: ActivityPage = { content: [], total: 0, page: 0, size: 25, pages: 0, actions: [] };
  activityPage = 0;
  filterUser = '';
  filterAction = '';

  newUser = '';
  newPass = '';
  newRole = 'USER';
  userMessage = '';

  ngOnInit(): void {
    this.api.me().subscribe({
      next: user => {
        this.user = user;
        this.start();
      },
      error: () => {
        this.api.setupStatus().subscribe({
          next: status => {
            this.setupRequired = status.required;
            this.loading = false;
          },
          error: () => {
            this.loading = false;
            this.loginError = 'No se pudo conectar con el servidor';
          }
        });
      }
    });
  }

  private start(): void {
    this.loading = false;
    this.load(null);
    this.loadStats();
    this.loadActivity(0);
    if (this.isAdmin) {
      this.loadUsers();
    }
  }

  get isAdmin(): boolean {
    return this.user?.role === 'ADMIN';
  }

  private setNotice(message: string): void {
    this.notice = message;
    this.refresh();
  }

  private refresh(): void {
    try {
      this.cdr.detectChanges();
    } catch {
      // fuera de un ciclo de detección: Angular lo hará solo
    }
  }

  login(): void {
    if (!this.loginUser.trim() || !this.loginPass) {
      this.loginError = 'Escribe usuario y contraseña';
      return;
    }
    this.loginBusy = true;
    this.loginError = '';
    this.api.login(this.loginUser.trim(), this.loginPass).subscribe({
      next: user => {
        this.user = user;
        this.loginBusy = false;
        this.loginPass = '';
        this.start();
      },
      error: () => {
        this.loginBusy = false;
        this.loginError = 'Usuario o contraseña incorrectos';
      }
    });
  }

  setup(): void {
    const username = this.setupUser.trim();
    if (!username || !this.setupPass) {
      this.setupError = 'Escribe usuario y contraseña';
      return;
    }
    if (this.setupPass.length < 8) {
      this.setupError = 'La contraseña debe tener al menos 8 caracteres';
      return;
    }
    if (this.setupPass !== this.setupPassConfirm) {
      this.setupError = 'Las contraseñas no coinciden';
      return;
    }
    this.setupBusy = true;
    this.setupError = '';
    this.api.setup(username, this.setupPass).subscribe({
      next: user => {
        this.user = user;
        this.setupBusy = false;
        this.setupRequired = false;
        this.setupPass = '';
        this.setupPassConfirm = '';
        this.start();
      },
      error: err => {
        this.setupBusy = false;
        if (err?.status === 409) {
          this.setupRequired = false;
          this.setupError = 'Ya existe un administrador. Inicia sesión.';
        } else {
          this.setupError = 'No se pudo crear el administrador';
        }
      }
    });
  }

  logout(): void {
    this.api.logout().subscribe({
      next: () => this.resetSession(),
      error: () => this.resetSession()
    });
  }

  private resetSession(): void {
    this.user = null;
    this.listing = { parentId: null, breadcrumb: [], items: [], search: false };
    this.stats = null;
    this.activity = { content: [], total: 0, page: 0, size: 25, pages: 0, actions: [] };
    this.users = [];
    this.tab = 'files';
    this.query = '';
    this.selected.clear();
    this.preview = null;
  }

  load(parentId: string | null): void {
    this.api.list(parentId, this.query).subscribe({
      next: listing => {
        this.listing = listing;
        this.selected.clear();
        this.refresh();
      },
      error: () => this.setNotice('No se pudo cargar la carpeta')
    });
  }

  loadStats(): void {
    this.api.stats().subscribe({ next: s => (this.stats = s) });
  }

  loadUsers(): void {
    this.api.users().subscribe({ next: u => (this.users = u) });
  }

  /* ---------- búsqueda y orden ---------- */

  search(): void {
    this.selected.clear();
    this.load(this.query.trim() ? this.listing.parentId : this.listing.parentId);
  }

  clearSearch(): void {
    this.query = '';
  }

  setSort(key: SortKey): void {
    if (this.sortKey === key) {
      this.sortDir = -this.sortDir;
    } else {
      this.sortKey = key;
      this.sortDir = 1;
    }
  }

  get sortedItems(): Item[] {
    const items = [...this.listing.items];
    const dir = this.sortDir;
    items.sort((a, b) => {
      if (a.folder !== b.folder) {
        return a.folder ? -1 : 1;
      }
      switch (this.sortKey) {
        case 'size':
          return (a.size - b.size) * dir;
        case 'date':
          return (a.createdAt.localeCompare(b.createdAt)) * dir;
        case 'owner':
          return a.owner.localeCompare(b.owner) * dir;
        default:
          return a.name.localeCompare(b.name, 'es') * dir;
      }
    });
    return items;
  }

  /* ---------- navegación ---------- */

  goTo(parentId: string | null): void {
    this.query = '';
    this.load(parentId);
  }

  openItem(item: Item): void {
    if (item.folder) {
      this.query = '';
      this.load(item.id);
      return;
    }
    this.openPreview(item);
  }

  openPreview(item: Item): void {
    this.preview = item;
    this.previewText = '';
    this.previewLoading = this.previewKind(item) === 'text';
    if (this.previewLoading) {
      fetch(this.api.rawUrl(item.id), { credentials: 'same-origin' })
        .then(r => r.text())
        .then(text => {
          this.previewText = text.slice(0, 200000);
          this.previewLoading = false;
          this.refresh();
        })
        .catch(() => {
          this.previewText = 'No se pudo cargar el contenido.';
          this.previewLoading = false;
          this.refresh();
        });
    }
  }

  closePreview(): void {
    this.preview = null;
    this.previewText = '';
  }

  previewKind(item: Item): PreviewKind {
    const name = item.name.toLowerCase();
    if (/\.(jpg|jpeg|png|gif|bmp|webp)$/.test(name)) return 'image';
    if (/\.(mp4|m4v|webm|ogg|ogv)$/.test(name)) return 'video';
    if (/\.(mp3|m4a|aac|wav|flac|oga)$/.test(name)) return 'audio';
    if (name.endsWith('.pdf')) return 'pdf';
    if (/\.(txt|md|log|csv|json|xml|html|css|js|ts|java|py|sh|sql|ini|yml|yaml)$/.test(name)) return 'text';
    return 'other';
  }

  /* ---------- carpetas ---------- */

  openFolderDialog(): void {
    this.newFolderName = '';
    this.showFolderDialog = true;
  }

  createFolder(): void {
    const name = this.newFolderName.trim();
    if (!name) {
      return;
    }
    this.api.createFolder(this.listing.parentId, name).subscribe({
      next: () => {
        this.showFolderDialog = false;
        this.load(this.listing.parentId);
        this.loadStats();
        this.loadActivity(this.activityPage);
      },
      error: () => this.setNotice('No se pudo crear la carpeta')
    });
  }

  /* ---------- renombrar y mover ---------- */

  startRename(item: Item): void {
    this.renameItem = item;
    this.renameValue = item.name;
  }

  confirmRename(): void {
    if (!this.renameItem) {
      return;
    }
    const name = this.renameValue.trim();
    if (!name) {
      return;
    }
    this.api.rename(this.renameItem.id, name).subscribe({
      next: () => {
        this.renameItem = null;
        this.load(this.listing.parentId);
        this.loadActivity(this.activityPage);
        this.setNotice('Renombrado');
      },
      error: () => this.setNotice('No se pudo renombrar')
    });
  }

  startMove(item: Item): void {
    this.moveItem = item;
    this.moveTarget = item.folder ? this.listing.parentId : this.listing.parentId;
    this.folders = [];
    this.api.folders().subscribe({
      next: list => {
        this.folders = list.filter(f => f.id !== item.id);
        this.refresh();
      }
    });
  }

  confirmMove(): void {
    if (!this.moveItem) {
      return;
    }
    this.api.move(this.moveItem.id, this.moveTarget).subscribe({
      next: () => {
        this.moveItem = null;
        this.load(this.listing.parentId);
        this.loadActivity(this.activityPage);
        this.setNotice('Movido');
      },
      error: () => this.setNotice('No se pudo mover (destino no válido)')
    });
  }

  /* ---------- selección ---------- */

  toggle(item: Item): void {
    if (this.selected.has(item.id)) {
      this.selected.delete(item.id);
    } else {
      this.selected.add(item.id);
    }
  }

  isSelected(item: Item): boolean {
    return this.selected.has(item.id);
  }

  get allSelected(): boolean {
    return this.listing.items.length > 0 && this.selected.size === this.listing.items.length;
  }

  get selectedCount(): number {
    return this.selected.size;
  }

  toggleAll(): void {
    if (this.allSelected) {
      this.selected.clear();
      return;
    }
    this.selected.clear();
    this.listing.items.forEach(i => this.selected.add(i.id));
  }

  downloadSelected(): void {
    if (this.selected.size === 0) {
      return;
    }
    window.open(this.api.zipUrl([...this.selected]), '_blank');
    this.loadActivity(this.activityPage);
  }

  deleteSelected(): void {
    if (this.selected.size === 0) {
      return;
    }
    if (!confirm(`¿Borrar ${this.selected.size} elemento(s) seleccionado(s)?`)) {
      return;
    }
    this.api.batchDelete([...this.selected]).subscribe({
      next: res => {
        this.selected.clear();
        this.load(this.listing.parentId);
        this.loadStats();
        this.loadActivity(this.activityPage);
        this.setNotice(res.denied.length
          ? `Borrados ${res.deleted}. Sin permiso: ${res.denied.join(', ')}`
          : `Borrados ${res.deleted} elemento(s)`);
      },
      error: () => this.setNotice('No se pudo borrar la selección')
    });
  }

  /* ---------- subida por trozos ---------- */

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging = false;
    const transfer = event.dataTransfer;
    if (!transfer) {
      return;
    }

    // Al arrastrar una carpeta, dataTransfer.files no contiene sus archivos.
    // Hay que recorrer las entradas del sistema de archivos del navegador.
    const entries: FileSystemEntry[] = [];
    if (transfer.items && transfer.items.length) {
      for (const item of Array.from(transfer.items)) {
        if (item.kind !== 'file') {
          continue;
        }
        const entry = item.webkitGetAsEntry?.();
        if (entry) {
          entries.push(entry);
        }
      }
    }

    if (entries.length) {
      void this.collectDropped(entries);
      return;
    }

    if (transfer.files && transfer.files.length) {
      void this.sendEntries(Array.from(transfer.files).map(file => ({ file, folders: [] })));
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragging = false;
  }

  onFilesPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length) {
      this.sendFiles(Array.from(input.files));
      input.value = '';
    }
  }

  onFolderPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    input.value = '';
    if (!files.length) {
      return;
    }

    const uploads: UploadEntry[] = [];
    const seenFolders = new Set<string>();
    for (const file of files) {
      const relative = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
      const parts = relative.split('/').filter(Boolean);
      if (parts.length <= 1) {
        uploads.push({ file, folders: [] });
        continue;
      }
      const folders = parts.slice(0, -1);
      for (let i = 1; i <= folders.length; i++) {
        const key = this.folderKey(folders.slice(0, i));
        if (!seenFolders.has(key)) {
          seenFolders.add(key);
          uploads.push({ file: null, folders: folders.slice(0, i) });
        }
      }
      uploads.push({ file, folders });
    }
    void this.sendEntries(uploads);
  }

  sendFiles(files: File[]): void {
    void this.sendEntries(files.map(file => ({ file, folders: [] })));
  }

  private async collectDropped(entries: FileSystemEntry[]): Promise<void> {
    try {
      const uploads: UploadEntry[] = [];
      for (const entry of entries) {
        await this.walkEntry(entry, [], uploads);
      }
      await this.sendEntries(uploads);
    } catch {
      this.setNotice('No se pudieron leer las carpetas arrastradas');
    }
  }

  private async walkEntry(entry: FileSystemEntry, folders: string[], out: UploadEntry[]): Promise<void> {
    if (entry.isFile) {
      const file = await new Promise<File>((resolve, reject) => {
        (entry as FileSystemFileEntry).file(resolve, reject);
      });
      out.push({ file, folders });
      return;
    }
    if (!entry.isDirectory) {
      return;
    }

    const path = [...folders, entry.name];
    // Se añade también la carpeta para crearla aunque esté vacía.
    out.push({ file: null, folders: path });

    const reader = (entry as FileSystemDirectoryEntry).createReader();
    while (true) {
      const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => {
        reader.readEntries(resolve, reject);
      });
      if (!batch.length) {
        break;
      }
      for (const child of batch) {
        await this.walkEntry(child, path, out);
      }
    }
  }

  private folderKey(parts: string[]): string {
    return parts.join('/').toLowerCase();
  }

  private async ensureFolders(entries: UploadEntry[], parentId: string | null): Promise<Map<string, string>> {
    const existing = await firstValueFrom(this.api.folders());
    const ids = new Map<string, string | null>();
    for (const folder of existing) {
      ids.set(folder.path.toLowerCase(), folder.id);
    }

    const base = this.listing.breadcrumb.map(item => item.name);
    const baseKey = base.join('/').toLowerCase();
    ids.set(baseKey, parentId);

    const wanted = new Map<string, string[]>();
    for (const entry of entries) {
      for (let i = 1; i <= entry.folders.length; i++) {
        const segments = entry.folders.slice(0, i);
        wanted.set(this.folderKey(segments), segments);
      }
    }

    const result = new Map<string, string>();
    const pending = Array.from(wanted.entries()).sort((a, b) => a[1].length - b[1].length);
    for (const [key, segments] of pending) {
      const absolute = [...base, ...segments].join('/').toLowerCase();
      const found = ids.get(absolute);
      if (typeof found === 'string') {
        result.set(key, found);
        continue;
      }
      const parentKey = [...base, ...segments.slice(0, -1)].join('/').toLowerCase();
      const parent = ids.get(parentKey) ?? null;
      const created = await firstValueFrom(this.api.createFolder(parent, segments[segments.length - 1]));
      ids.set(absolute, created.id);
      result.set(key, created.id);
    }
    return result;
  }

  async sendEntries(entries: UploadEntry[]): Promise<void> {
    if (this.uploading || !entries.length) {
      return;
    }
    this.uploading = true;
    this.cancelRequested = false;
    this.uploadPercent = 0;
    this.uploadNames = 'preparando…';
    this.uploadText = '';
    this.refresh();

    const parentId = this.listing.parentId;
    let files = 0;
    let folderCount = 0;

    try {
      const folders = await this.ensureFolders(entries, parentId);
      const folderKeys = new Set<string>();
      for (const entry of entries) {
        for (let i = 1; i <= entry.folders.length; i++) {
          folderKeys.add(this.folderKey(entry.folders.slice(0, i)));
        }
      }
      folderCount = folderKeys.size;
      this.uploadNames = folderCount ? 'carpetas y ficheros' : 'ficheros';
      this.uploadText = folderCount ? `${folderCount} carpeta(s)` : '';
      this.refresh();

      for (const entry of entries) {
        if (!entry.file) {
          continue;
        }
        const key = this.folderKey(entry.folders);
        const target = entry.folders.length ? (folders.get(key) ?? parentId) : parentId;
        this.uploadNames = [...entry.folders, entry.file.name].join('/');
        this.uploadPercent = 0;
        this.uploadText = '';
        this.refresh();
        await this.uploadOne(entry.file, target);
        files++;
      }
    } catch {
      const failedName = this.uploadNames;
      const cancelled = this.cancelRequested;
      if (this.currentUploadId) {
        this.api.uploadCancel(this.currentUploadId).subscribe({ error: () => undefined });
        this.currentUploadId = null;
      }
      this.finishUpload();
      this.load(parentId);
      this.loadStats();
      this.setNotice(cancelled ? 'Subida cancelada' : `Error al subir ${failedName || 'la selección'}`);
      return;
    }

    this.finishUpload();
    this.load(parentId);
    this.loadStats();
    this.loadActivity(this.activityPage);
    if (!files && folderCount) {
      this.setNotice(`${folderCount} carpeta(s) creada(s)`);
    } else if (folderCount) {
      this.setNotice(`${files} fichero(s) subido(s) en ${folderCount} carpeta(s)`);
    } else {
      this.setNotice(files === 1 ? 'Fichero subido' : `${files} ficheros subidos`);
    }
  }

  private async uploadOne(file: File, parentId: string | null): Promise<void> {
    const fingerprint = `${file.name}:${file.size}:${file.lastModified}`;
    const init = await firstValueFrom(this.api.uploadInit({
      parentId,
      name: file.name,
      size: file.size,
      contentType: file.type || 'application/octet-stream',
      fingerprint
    }));
    this.currentUploadId = init.uploadId;
    let offset = init.received;
    const chunkSize = 8 * 1024 * 1024;

    while (offset < file.size) {
      if (this.cancelRequested) {
        throw new Error('cancel');
      }
      const end = Math.min(offset + chunkSize, file.size);
      const chunk = file.slice(offset, end);
      const result = await firstValueFrom(this.api.uploadChunk(init.uploadId, offset, chunk));
      offset = result.received;
      this.uploadPercent = Math.round((offset / file.size) * 100);
      this.uploadText = `${this.fmt(offset)} de ${this.fmt(file.size)}`;
      this.refresh();
    }

    await firstValueFrom(this.api.uploadComplete(init.uploadId));
    this.currentUploadId = null;
  }

  cancelUpload(): void {
    this.cancelRequested = true;
  }

  private finishUpload(): void {
    this.uploading = false;
    this.uploadPercent = 0;
    this.uploadNames = '';
    this.uploadText = '';
    this.refresh();
  }

  /* ---------- acciones por fichero ---------- */

  download(item: Item): void {
    window.open(this.api.downloadUrl(item.id), '_blank');
    this.loadActivity(this.activityPage);
  }

  share(item: Item): void {
    this.api.share(item.id).subscribe({
      next: res => {
        const url = `${window.location.origin}/s/${res.token}`;
        this.setNotice(`Enlace: ${url}`);
        if (navigator.clipboard?.writeText) {
          navigator.clipboard.writeText(url).then(
            () => this.setNotice(`Enlace copiado: ${url}`),
            () => undefined
          );
        }
        this.loadActivity(this.activityPage);
      },
      error: () => this.setNotice('No se pudo crear el enlace')
    });
  }

  remove(item: Item): void {
    const label = item.folder ? `la carpeta "${item.name}" y todo su contenido` : `"${item.name}"`;
    if (!confirm(`¿Borrar ${label}?`)) {
      return;
    }
    this.api.remove(item.id).subscribe({
      next: () => {
        this.load(this.listing.parentId);
        this.loadStats();
        this.loadActivity(this.activityPage);
      },
      error: () => this.setNotice('No tienes permiso para borrar esto')
    });
  }

  canDelete(item: Item): boolean {
    return this.isAdmin || item.owner === this.user?.username;
  }

  /* ---------- actividad ---------- */

  loadActivity(page: number): void {
    this.activityPage = page;
    this.api.activity(page, 25, this.filterUser, this.filterAction).subscribe({
      next: data => {
        this.activity = data;
        this.refresh();
      }
    });
  }

  applyActivityFilters(): void {
    this.loadActivity(0);
  }

  activityPrev(): void {
    if (this.activityPage > 0) {
      this.loadActivity(this.activityPage - 1);
    }
  }

  activityNext(): void {
    if (this.activityPage + 1 < this.activity.pages) {
      this.loadActivity(this.activityPage + 1);
    }
  }

  /* ---------- usuarios ---------- */

  createUser(): void {
    if (!this.newUser.trim() || !this.newPass) {
      this.userMessage = 'Escribe usuario y contraseña';
      return;
    }
    this.api.createUser(this.newUser.trim(), this.newPass, this.newRole).subscribe({
      next: () => {
        this.userMessage = `Usuario ${this.newUser.trim()} creado`;
        this.newUser = '';
        this.newPass = '';
        this.loadUsers();
        this.loadActivity(this.activityPage);
      },
      error: () => (this.userMessage = 'No se pudo crear (¿ya existe?)')
    });
  }

  /* ---------- utilidades ---------- */

  fmt(bytes: number): string {
    if (bytes === null || bytes === undefined) {
      return '-';
    }
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
  }

  date(value: string): string {
    if (!value) {
      return '';
    }
    return new Date(value).toLocaleString('es-ES');
  }

  usedPercent(): number {
    if (!this.stats || !this.stats.total) {
      return 0;
    }
    return Math.round((this.stats.used / this.stats.total) * 100);
  }

  fileBadge(item: Item): { label: string; cls: string } {
    if (item.folder) {
      return { label: '', cls: 'folder' };
    }
    const dot = item.name.lastIndexOf('.');
    const ext = dot > 0 ? item.name.substring(dot + 1).toLowerCase() : '';
    const map: Record<string, string> = {
      mkv: 'video', mp4: 'video', avi: 'video', mov: 'video', m4v: 'video', wmv: 'video', webm: 'video', ts: 'video',
      mp3: 'audio', flac: 'audio', wav: 'audio', ogg: 'audio', m4a: 'audio', aac: 'audio',
      jpg: 'image', jpeg: 'image', png: 'image', gif: 'image', webp: 'image', bmp: 'image', svg: 'image',
      pdf: 'pdf',
      doc: 'doc', docx: 'doc', odt: 'doc', rtf: 'doc', txt: 'doc', md: 'doc',
      xls: 'sheet', xlsx: 'sheet', ods: 'sheet', csv: 'sheet',
      zip: 'archive', rar: 'archive', '7z': 'archive', tar: 'archive', gz: 'archive', bz2: 'archive',
      iso: 'disk', img: 'disk', bin: 'disk',
      js: 'code', json: 'code', xml: 'code', html: 'code', css: 'code', java: 'code', py: 'code', sh: 'code', sql: 'code'
    };
    return { label: (ext ? ext.toUpperCase().slice(0, 4) : 'FILE'), cls: map[ext] ?? 'other' };
  }

  isImage(item: Item): boolean {
    return /\.(jpg|jpeg|png|gif|bmp)$/i.test(item.name);
  }

  onThumbError(id: string): void {
    this.thumbFailed.add(id);
  }

  thumbOk(item: Item): boolean {
    return this.isImage(item) && !this.thumbFailed.has(item.id);
  }

  rawUrl(id: string): string {
    return this.api.rawUrl(id);
  }

  downloadUrl(id: string): string {
    return this.api.downloadUrl(id);
  }

  thumbUrl(id: string): string {
    return this.api.thumbUrl(id);
  }
}
