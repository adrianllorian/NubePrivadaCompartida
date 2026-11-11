import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ActivityPage, FolderDto, Item, Listing, SetupStatus, Stats, UploadChunk, UploadInit, User } from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  login(username: string, password: string): Observable<User> {
    return this.http.post<User>('/api/auth/login', { username, password });
  }

  setupStatus(): Observable<SetupStatus> {
    return this.http.get<SetupStatus>('/api/setup');
  }

  setup(username: string, password: string): Observable<User> {
    return this.http.post<User>('/api/setup', { username, password });
  }

  me(): Observable<User> {
    return this.http.get<User>('/api/auth/me');
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {});
  }

  list(parentId: string | null, query = ''): Observable<Listing> {
    const params: string[] = [];
    if (parentId) {
      params.push('parentId=' + encodeURIComponent(parentId));
    }
    if (query) {
      params.push('q=' + encodeURIComponent(query));
    }
    return this.http.get<Listing>('/api/files' + (params.length ? '?' + params.join('&') : ''));
  }

  folders(): Observable<FolderDto[]> {
    return this.http.get<FolderDto[]>('/api/folders');
  }

  createFolder(parentId: string | null, name: string): Observable<Item> {
    return this.http.post<Item>('/api/folder', { parentId, name });
  }

  rename(id: string, name: string): Observable<Item> {
    return this.http.post<Item>(`/api/files/${id}/rename`, { name });
  }

  move(id: string, parentId: string | null): Observable<Item> {
    return this.http.post<Item>(`/api/files/${id}/move`, { parentId });
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`/api/files/${id}`);
  }

  batchDelete(ids: string[]): Observable<{ deleted: number; denied: string[] }> {
    return this.http.post<{ deleted: number; denied: string[] }>('/api/files/batch-delete', { ids });
  }

  share(id: string): Observable<{ token: string; url: string }> {
    return this.http.post<{ token: string; url: string }>(`/api/files/${id}/share`, {});
  }

  stats(): Observable<Stats> {
    return this.http.get<Stats>('/api/stats');
  }

  activity(page: number, size: number, user = '', action = ''): Observable<ActivityPage> {
    const params = [`page=${page}`, `size=${size}`];
    if (user) {
      params.push('user=' + encodeURIComponent(user));
    }
    if (action) {
      params.push('action=' + encodeURIComponent(action));
    }
    return this.http.get<ActivityPage>('/api/activity?' + params.join('&'));
  }

  users(): Observable<User[]> {
    return this.http.get<User[]>('/api/users');
  }

  createUser(username: string, password: string, role: string): Observable<User> {
    return this.http.post<User>('/api/users', { username, password, role });
  }

  uploadInit(body: { parentId: string | null; name: string; size: number; contentType: string; fingerprint: string }): Observable<UploadInit> {
    return this.http.post<UploadInit>('/api/uploads/init', body);
  }

  uploadChunk(uploadId: string, offset: number, chunk: Blob): Observable<UploadChunk> {
    return this.http.post<UploadChunk>(`/api/uploads/${uploadId}/chunk?offset=${offset}`, chunk, {
      headers: { 'Content-Type': 'application/octet-stream' }
    });
  }

  uploadComplete(uploadId: string): Observable<{ id: string }> {
    return this.http.post<{ id: string }>(`/api/uploads/${uploadId}/complete`, {});
  }

  uploadCancel(uploadId: string): Observable<void> {
    return this.http.delete<void>(`/api/uploads/${uploadId}`);
  }

  downloadUrl(id: string): string {
    return `/api/files/${id}/download`;
  }

  rawUrl(id: string): string {
    return `/api/files/${id}/raw`;
  }

  thumbUrl(id: string): string {
    return `/api/files/${id}/thumb`;
  }

  zipUrl(ids: string[]): string {
    return `/api/files/zip?ids=${encodeURIComponent(ids.join(','))}`;
  }
}
