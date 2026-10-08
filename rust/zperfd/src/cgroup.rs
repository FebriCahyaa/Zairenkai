// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Capability-aware per-thread cgroup/cpuset controller.
//!
//! Only cgroup-v2 parents which already delegate the cpuset controller are
//! eligible. Zairenkai never enables controllers globally. A transient child
//! is created under the task's existing cgroup, the thread is moved with
//! `cgroup.threads`, and the original membership is restored on reset.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::time::{Duration, Instant};

const CAPABILITY_REFRESH: Duration = Duration::from_secs(5);

#[derive(Debug,Clone,Copy,PartialEq,Eq)]
pub enum CgroupMode { V2, Unsupported }

#[derive(Debug,Clone,Copy,Default,PartialEq,Eq)]
pub struct CgroupCapabilities {
    pub mode: Option<CgroupMode>,
    pub per_thread: bool,
    pub cpuset: bool,
    pub delegated: bool,
}

#[derive(Clone,Debug)]
struct Assignment { tid:i32, start_time_ticks:u64, original:String, managed:String, cpus:Vec<usize> }

pub struct ThreadCgroupController {
    assignments:BTreeMap<i32,Assignment>,
    managed_groups:BTreeMap<String,u32>,
    capabilities:CgroupCapabilities,
    last_discovery:Option<Instant>,
    capability_parent:Option<String>,
}

impl Default for ThreadCgroupController { fn default()->Self{Self::new()} }

impl ThreadCgroupController {
    pub fn new()->Self{Self{assignments:BTreeMap::new(),managed_groups:BTreeMap::new(),capabilities:CgroupCapabilities::default(),last_discovery:None,capability_parent:None}}
    pub fn capabilities(&self)->CgroupCapabilities{self.capabilities}
    pub fn reset(&mut self,s:&Sysroot){
        let assignments=self.assignments.values().cloned().collect::<Vec<_>>();
        for a in assignments {
            if read_start_time(s,a.tid)==Some(a.start_time_ticks) {
                let _=write_threads(s,&a.original,a.tid);
            }
        }
        for (path,_) in self.managed_groups.clone(){ let _=remove_managed(s,&path); }
        self.assignments.clear(); self.managed_groups.clear(); self.capabilities=CgroupCapabilities::default(); self.last_discovery=None; self.capability_parent=None;
    }

    /// Returns Ok(true) only when the thread was moved by Zairenkai. False is
    /// a capability/permission miss and is intended to trigger affinity fallback.
    pub fn apply(&mut self,s:&Sysroot,tid:i32,start:u64,cpus:&[usize])->Result<bool,String>{
        if tid<=0 || start==0 || cpus.is_empty(){return Ok(false);}
        // Once a task is inside a Zairenkai transient cgroup, never re-run
        // discovery against the child. The capability contract belongs to the
        // original delegated parent; re-discovering the child can incorrectly
        // report cpuset as unavailable and strand the assignment.
        if let Some(a)=self.assignments.get(&tid){
            if a.start_time_ticks==start {
                if read_start_time(s,tid)!=Some(start){return Err("thread identity changed before cgroup actuation".into());}
                let target = normalize_cpu_list(cpus);
                if target == a.cpus {
                    return Ok(true);
                }
                s.write(&format!("{}/cpuset.cpus",a.managed),&format_cpu_list(&target)).map_err(|e|format!("cpuset.cpus: {e}"))?;
                if let Some(current)=self.assignments.get_mut(&tid) { current.cpus=target; }
                return Ok(true);
            }
            self.assignments.remove(&tid);
        }
        self.discover(s,tid)?;
        if !self.capabilities.per_thread || !self.capabilities.cpuset || !self.capabilities.delegated {return Ok(false);}
        if read_start_time(s,tid)!=Some(start){return Err("thread identity changed before cgroup actuation".into());}
        let original=thread_cgroup_v2(s,tid).ok_or_else(||"thread is not in cgroup-v2".to_string())?;
        let parent_abs=format!("/sys/fs/cgroup{}",original);
        let effective_cpus=s.read(&format!("{parent_abs}/cpuset.cpus.effective")).unwrap_or_default();
        let effective=parse_cpu_list(&effective_cpus);
        let target=normalize_cpu_list(&cpus.iter().copied().filter(|c|effective.binary_search(c).is_ok()).collect::<Vec<_>>());
        if target.is_empty(){return Ok(false);}
        let mems=s.read(&format!("{parent_abs}/cpuset.mems.effective")).filter(|v|!v.trim().is_empty()).unwrap_or_else(||"0".into());
        let name=format!(".zairenkai-{}-{:x}",std::process::id(),stable_id(&original));
        let child_rel=join_cgroup_rel(&original,&name).ok_or_else(||"invalid cgroup path".to_string())?;
        let child_abs=format!("/sys/fs/cgroup{}",child_rel);
        if !s.exists(&child_abs){ fs::create_dir(s.path(&child_abs)).map_err(|e|format!("create transient cgroup: {e}"))?; }
        if s.read(&format!("{child_abs}/cpuset.mems")).is_none() { let _=s.write(&format!("{child_abs}/cpuset.mems"),&mems); }
        s.write(&format!("{child_abs}/cpuset.cpus"),&format_cpu_list(&target)).map_err(|e|format!("cpuset.cpus: {e}"))?;
        s.write(&format!("{child_abs}/cpuset.mems"),&mems).map_err(|e|format!("cpuset.mems: {e}"))?;
        write_threads(s,&child_rel,tid).map_err(|e|format!("move thread into cgroup: {e}"))?;
        self.assignments.insert(tid,Assignment{tid,start_time_ticks:start,original,managed:child_rel.clone(),cpus:target});
        *self.managed_groups.entry(child_rel).or_insert(0)+=1;
        Ok(true)
    }

    fn discover(&mut self,s:&Sysroot,tid:i32)->Result<(),String>{
        let root="/sys/fs/cgroup";
        let original=thread_cgroup_v2(s,tid).unwrap_or_default();
        if self.capability_parent.as_deref()==Some(original.as_str())
            && self.last_discovery.is_some_and(|t| t.elapsed() < CAPABILITY_REFRESH)
        {
            return Ok(());
        }
        if !s.exists(&format!("{root}/cgroup.controllers")){self.capabilities=CgroupCapabilities{mode:Some(CgroupMode::Unsupported),..Default::default()}; self.last_discovery=Some(Instant::now()); self.capability_parent=Some(original); return Ok(());}
        let parent=format!("{root}{original}");
        let subtree=s.read(&format!("{parent}/cgroup.subtree_control")).unwrap_or_default();
        // We require cpuset to already be enabled in subtree_control. Never
        // mutate it globally because that changes the semantics of sibling apps.
        let delegated=subtree.split_whitespace().any(|v|v=="cpuset");
        self.capabilities=CgroupCapabilities{mode:Some(CgroupMode::V2),per_thread:s.exists(&format!("{parent}/cgroup.threads")),cpuset:s.exists(&format!("{parent}/cpuset.cpus"))&&s.exists(&format!("{parent}/cpuset.mems")),delegated};
        self.last_discovery=Some(Instant::now());
        self.capability_parent=Some(original);
        Ok(())
    }
}

fn thread_cgroup_v2(s:&Sysroot,tid:i32)->Option<String>{
    let text=s.read(&format!("/proc/{tid}/cgroup"))?;
    for line in text.lines(){ let mut p=line.splitn(3,':'); let hierarchy=p.next()?; let subs=p.next()?; let path=p.next()?; if hierarchy=="0"&&subs.is_empty()&&safe_rel(path){return Some(if path=="/"{"/".into()}else{path.to_string()});} }
    None
}
fn write_threads(s:&Sysroot,rel:&str,tid:i32)->io::Result<()> { let p=if rel=="/"{"/sys/fs/cgroup/cgroup.threads".into()}else{format!("/sys/fs/cgroup{rel}/cgroup.threads")}; s.write(&p,&tid.to_string()) }
fn remove_managed(s:&Sysroot,rel:&str)->io::Result<()> { let p=if rel=="/"{return Ok(())}else{ s.path(&format!("/sys/fs/cgroup{rel}")) }; fs::remove_dir(p) }
fn safe_rel(path:&str)->bool{ path=="/" || (path.starts_with('/') && !path.split('/').any(|x|x==".."||x.contains('\0'))) }
fn join_cgroup_rel(parent:&str,name:&str)->Option<String>{ if !name.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'.'||b==b'-'||b==b'_'){return None;} Some(if parent=="/"{format!("/{name}")}else{format!("{parent}/{name}")}) }
fn stable_id(s:&str)->u64{ let mut h=0xcbf29ce484222325u64; for b in s.as_bytes(){h^=*b as u64;h=h.wrapping_mul(0x100000001b3);} h }
fn parse_cpu_list(s:&str)->Vec<usize>{ let mut out=Vec::new(); for token in s.split(',').map(str::trim){ if let Some((a,b))=token.split_once('-'){if let(Ok(a),Ok(b))=(a.parse::<usize>(),b.parse::<usize>()){out.extend(a.min(b)..=a.max(b));}} else if let Ok(v)=token.parse(){out.push(v);} } out.sort_unstable();out.dedup();out }
fn normalize_cpu_list(cpus:&[usize])->Vec<usize>{ let mut c=cpus.to_vec(); c.sort_unstable(); c.dedup(); c }
fn format_cpu_list(cpus:&[usize])->String{ let mut c=cpus.to_vec(); c.sort_unstable();c.dedup(); let mut out=String::new(); let mut i=0; while i<c.len(){ let start=c[i]; let mut end=start; while i+1<c.len()&&c[i+1]==end+1{i+=1;end=c[i];} if !out.is_empty(){out.push(',');} if start==end{out.push_str(&start.to_string())}else{out.push_str(&format!("{start}-{end}"));} i+=1;} out }
fn read_start_time(s:&Sysroot,tid:i32)->Option<u64>{let text=s.read(&format!("/proc/{tid}/stat"))?;let close=text.rfind(") ")?;text.get(close+2..)?.split_whitespace().nth(19)?.parse().ok()}

#[cfg(test)]
mod tests{use super::*; #[test] fn cpu_format_is_compact(){assert_eq!(format_cpu_list(&[0,1,2,4,6,7]),"0-2,4,6-7");} #[test] fn cpu_normalization_is_stable(){assert_eq!(normalize_cpu_list(&[3,1,3,2,1]),vec![1,2,3]);} #[test] fn safe_cgroup_paths_reject_parent(){assert!(!safe_rel("/x/../y"));assert!(safe_rel("/a/b"));}}
