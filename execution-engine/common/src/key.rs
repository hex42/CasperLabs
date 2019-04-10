use super::alloc::vec::Vec;
use super::bytesrepr::{Error, FromBytes, ToBytes, N32, U32_SIZE};
use crate::contract_api::pointers::*;
use core::cmp::Ordering;
use core::ops::Add;

#[allow(clippy::derive_hash_xor_eq)]
#[repr(C)]
#[derive(PartialEq, Eq, Clone, Copy, Debug, Hash)]
pub enum AccessRights {
    Eqv,
    Read,
    Write,
    Add,
    ReadAdd,
    ReadWrite,
    AddWrite,
}

impl AccessRights {
    pub fn is_readable(self) -> bool {
        self >= AccessRights::Read
    }

    pub fn is_writeable(self) -> bool {
        self >= AccessRights::Write
    }

    pub fn is_addable(self) -> bool {
        self >= AccessRights::Add
    }
}

use AccessRights::*;

impl Add for AccessRights {
    type Output = AccessRights;

    fn add(self, other: AccessRights) -> AccessRights {
        match (self, other) {
            (Eqv, _) => other,
            (_, Eqv) => self,
            (Read, Read) => Read,
            (Write, Write) => Write,
            (Add, Add) => Add,
            (ReadAdd, ReadAdd) => ReadAdd,
            (ReadWrite, ReadWrite) => ReadWrite,
            (AddWrite, AddWrite) => AddWrite,
            (Read, Write) | (Write, Read) => ReadWrite,
            (Read, Add) | (Add, Read) => ReadAdd,
            (Write, Add) | (Add, Write) => AddWrite,
            // Because ReadWrite >= Add
            (ReadAdd, AddWrite) | (AddWrite, ReadAdd) => ReadWrite,
            (ReadWrite, AddWrite) | (AddWrite, ReadWrite) => ReadWrite,
            (Read, AddWrite) | (AddWrite, Read) => ReadWrite,
            (Write, ReadAdd) | (ReadAdd, Write) => ReadWrite,
            (Add, ReadWrite) | (ReadWrite, Add) => ReadWrite,
            (ReadAdd, ReadWrite) | (ReadWrite, ReadAdd) => ReadWrite,
            (Read, ReadAdd) | (ReadAdd, Read) => ReadAdd,
            (Read, ReadWrite) | (ReadWrite, Read) => ReadWrite,
            (Write, ReadWrite) | (ReadWrite, Write) => ReadWrite,
            (Write, AddWrite) | (AddWrite, Write) => AddWrite,
            (Add, ReadAdd) | (ReadAdd, Add) => ReadAdd,
            (Add, AddWrite) | (AddWrite, Add) => AddWrite,
        }
    }
}

/// Partial order of the access rights.
/// Since there are three distinct types of access rights to a resource
/// (Read, Write, Add; Eqv is implicit for each one), and various combinations
/// of them, some of them can be compared to each other.
///
/// This ordering is created so that it is possible to do:
///
/// ```
/// use casperlabs_contract_ffi::key::AccessRights;
///
/// // Imaginary definition of Key
/// pub struct Key {
///   pub access_right: AccessRights,
/// };
///
/// impl Key {
///   fn new(access_right: AccessRights) -> Key {
///     Key { access_right }
///   }
/// }
///
/// // Imaginary definition of resources to which we want restrict access to.
/// type Resource = u32;
///
/// // Imaginary error
/// type Error = String;
///
/// fn read(resource: Resource, key: Key) -> Result<Resource, Error> {
///   // note that the test is "greater or equal".
///   // This will pass for Read, ReadWrite, ReadAdd access rights.
///   if key.access_right >= AccessRights::Read {
///     Ok(resource)
///   } else {
///     Err("Invalid access rights to the resource.".to_owned())
///   }
/// }
///
/// assert!(read(10u32, Key::new(AccessRights::Read)).is_ok());
/// assert!(read(10u32, Key::new(AccessRights::ReadAdd)).is_ok());
/// assert!(read(10u32, Key::new(AccessRights::ReadWrite)).is_ok());
/// assert!(read(10u32, Key::new(AccessRights::Write)).is_err());
/// ```
///
///
/// and the tests passes for: Read, ReadAdd and ReadWrite.
impl PartialOrd for AccessRights {
    //  !!! Caution !!! Do not reorder
    fn partial_cmp(&self, other: &AccessRights) -> Option<Ordering> {
        match (self, other) {
            (Eqv, Eqv) => Some(Ordering::Equal),
            (Eqv, _) => Some(Ordering::Less),
            (_, Eqv) => Some(Ordering::Greater),
            (Read, Write) => None,
            (Write, Read) => None,
            (Read, Add) => None,
            (Add, Read) => None,
            (Read, ReadAdd) => Some(Ordering::Less),
            (ReadAdd, Read) => Some(Ordering::Greater),
            (Read, ReadWrite) => Some(Ordering::Less),
            (ReadWrite, Read) => Some(Ordering::Greater),
            (Write, Add) => None,
            (Add, Write) => None,
            (Read, AddWrite) => None,
            (Add, AddWrite) => Some(Ordering::Less),
            (Write, AddWrite) => Some(Ordering::Less),
            (ReadAdd, AddWrite) => None,
            (ReadWrite, AddWrite) => None,
            (AddWrite, Read) => None,
            (AddWrite, Add) => Some(Ordering::Greater),
            (AddWrite, Write) => Some(Ordering::Greater),
            (AddWrite, ReadAdd) => None,
            (AddWrite, ReadWrite) => None,
            (Write, ReadWrite) => Some(Ordering::Less),
            (ReadWrite, Write) => Some(Ordering::Greater),
            (Add, ReadAdd) => Some(Ordering::Less),
            (ReadAdd, Add) => Some(Ordering::Greater),
            // Because Read + Write can simulate Add,
            // and we want to promote the usage of Add,
            // it is the case that ReadWrite >= Add.
            (Add, ReadWrite) => Some(Ordering::Less),
            (ReadWrite, Add) => Some(Ordering::Greater),
            // In theory Write and ReadAdd should be comparable.
            // Assuming that we allow for using Add on the selected set of types
            // (for which there exists a Monoid instace) it should be the case
            // that Read + Add == Write (reading and modifying should be a write).
            // Examples:
            // 1)                  | 2)
            // init_value = 10     | init_value = 10
            // Read + Add(2) = 12  | Read + Add(-2) = 8
            // Write(12)           | Write(8)
            // The problem is that we haven't yet figured out how to accommodate
            // for "negative" operations. Especially how to encode entry removal
            // from a map using an Add.
            // For the safety and correctness reasons I've chosen to make these
            // operations incomparable.
            (Write, ReadAdd) => None,
            (ReadAdd, Write) => None,
            (ReadAdd, ReadWrite) => None,
            (ReadWrite, ReadAdd) => None,
            (_, _) => {
                // Every enum variant is equal to itself.
                if self == other {
                    Some(Ordering::Equal)
                } else {
                    None
                }
            }
        }
    }
}

pub const KEY_SIZE: usize = 32;

#[repr(C)]
#[derive(PartialEq, Eq, Clone, Copy, Debug, Hash, PartialOrd)]
pub enum Key {
    Account([u8; 20]),
    Hash([u8; 32]),
    URef([u8; 32], AccessRights), //TODO: more bytes?
}

use Key::*;
// TODO: I had to remove Ord derived on the Key enum because
// there is no total ordering of the AccessRights but Ord for the Key
// is required by the collections (HashMap, BTreeMap) so I decided to
// implement it by hand by just ignoring the access rights for the URef
// and falling back to the default Ord for the enum variants (top to bottom).
impl Ord for Key {
    fn cmp(&self, other: &Key) -> Ordering {
        match (self, other) {
            (Account(id_1), Account(id_2)) => id_1.cmp(id_2),
            (Account(_), Hash(_)) => Ordering::Less,
            (Account(_), URef(_, ..)) => Ordering::Less,
            (Hash(id_1), Hash(id_2)) => id_1.cmp(id_2),
            (Hash(_), URef(_, _)) => Ordering::Less,
            (Hash(_), Account(_)) => Ordering::Greater,
            (URef(id_1, ..), URef(id_2, ..)) => id_1.cmp(id_2),
            (URef(_, _), Account(_)) => Ordering::Greater,
            (URef(_, _), Hash(_)) => Ordering::Greater,
        }
    }
}

impl Key {
    pub fn to_u_ptr<T>(self) -> Option<UPointer<T>> {
        if let URef(id, access_right) = self {
            Some(UPointer::new(id, access_right))
        } else {
            None
        }
    }

    pub fn to_c_ptr(self) -> Option<ContractPointer> {
        match self {
            URef(id, rights) => Some(ContractPointer::URef(UPointer::new(id, rights))),
            Hash(id) => Some(ContractPointer::Hash(id)),
            _ => None,
        }
    }
}

const ACCOUNT_ID: u8 = 0;
const HASH_ID: u8 = 1;
const UREF_ID: u8 = 2;
const KEY_ID_SIZE: usize = 1; // u8 used to determine the ID
const ACCESS_RIGHTS_SIZE: usize = 1; // u8 used to tag AccessRights
pub const UREF_SIZE: usize = U32_SIZE + N32 + KEY_ID_SIZE + ACCESS_RIGHTS_SIZE;

impl ToBytes for AccessRights {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        match self {
            AccessRights::Eqv => 1u8.to_bytes(),
            AccessRights::Read => 2u8.to_bytes(),
            AccessRights::Add => 3u8.to_bytes(),
            AccessRights::Write => 4u8.to_bytes(),
            AccessRights::ReadAdd => 5u8.to_bytes(),
            AccessRights::ReadWrite => 6u8.to_bytes(),
            AccessRights::AddWrite => 7u8.to_bytes(),
        }
    }
}

impl FromBytes for AccessRights {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        let access_rights = match id {
            1 => Ok(AccessRights::Eqv),
            2 => Ok(AccessRights::Read),
            3 => Ok(AccessRights::Add),
            4 => Ok(AccessRights::Write),
            5 => Ok(AccessRights::ReadAdd),
            6 => Ok(AccessRights::ReadWrite),
            7 => Ok(AccessRights::AddWrite),
            _ => Err(Error::FormattingError),
        };
        access_rights.map(|rights| (rights, rest))
    }
}

impl ToBytes for Key {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        match self {
            Account(addr) => {
                let mut result = Vec::with_capacity(25);
                result.push(ACCOUNT_ID);
                result.append(&mut (20u32).to_bytes()?);
                result.extend(addr);
                Ok(result)
            }
            Hash(hash) => {
                let mut result = Vec::with_capacity(37);
                result.push(HASH_ID);
                result.append(&mut hash.to_bytes()?);
                Ok(result)
            }
            URef(rf, access_rights) => {
                let mut result = Vec::with_capacity(UREF_SIZE);
                result.push(UREF_ID);
                result.append(&mut rf.to_bytes()?);
                result.append(&mut access_rights.to_bytes()?);
                Ok(result)
            }
        }
    }
}
impl FromBytes for Key {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            ACCOUNT_ID => {
                let (addr, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(rest)?;
                if addr.len() != 20 {
                    Err(Error::FormattingError)
                } else {
                    let mut addr_array = [0u8; 20];
                    addr_array.copy_from_slice(&addr);
                    Ok((Account(addr_array), rem))
                }
            }
            HASH_ID => {
                let (hash, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Hash(hash), rem))
            }
            UREF_ID => {
                let (rf, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                let (access_right, rem2): (AccessRights, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((URef(rf, access_right), rem2))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

impl FromBytes for Vec<Key> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, rest): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<Key> = Vec::with_capacity((size as usize) * UREF_SIZE);
        let mut stream = rest;
        for _ in 0..size {
            let (t, rem): (Key, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(t);
            stream = rem;
        }
        Ok((result, stream))
    }
}
impl ToBytes for Vec<Key> {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(4 + (size as usize) * UREF_SIZE);
        result.extend(size.to_bytes()?);
        result.extend(
            self.iter()
                .map(ToBytes::to_bytes)
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .flatten(),
        );
        Ok(result)
    }
}

impl AsRef<[u8]> for Key {
    fn as_ref(&self) -> &[u8] {
        match self {
            // TODO: need to distinguish between variants?
            Account(a) => a,
            Hash(h) => h,
            URef(u, ..) => u,
        }
    }
}

#[allow(clippy::unnecessary_operation)]
#[cfg(test)]
mod tests {
    use crate::gens::*;
    use crate::key::AccessRights::{self, *};
    use core::cmp::Ordering;
    use core::hash::{Hash, Hasher};
    use proptest::prelude::*;
    use siphasher::sip::SipHasher;

    fn test_capabilities(right: AccessRights, requires: AccessRights, is_true: bool) {
        assert_eq!(
            right >= requires,
            is_true,
            "{:?} isn't enough to perform {:?} operation",
            right,
            requires
        )
    }

    fn test_readable(right: AccessRights, is_true: bool) {
        test_capabilities(right, AccessRights::Read, is_true)
    }

    #[test]
    fn test_is_readable() {
        test_readable(Read, true);
        test_readable(ReadAdd, true);
        test_readable(ReadWrite, true);
        test_readable(Add, false);
        test_readable(AddWrite, false);
        test_readable(Eqv, false);
        test_readable(Write, false);
    }

    fn test_writable(right: AccessRights, is_true: bool) {
        test_capabilities(right, Write, is_true)
    }

    #[test]
    fn test_is_writable() {
        test_writable(Write, true);
        test_writable(ReadWrite, true);
        test_writable(AddWrite, true);
        test_writable(Eqv, false);
        test_writable(Read, false);
        test_writable(Add, false);
        test_writable(ReadAdd, false);
    }

    fn test_addable(right: AccessRights, is_true: bool) {
        test_capabilities(right, Add, is_true)
    }

    #[test]
    fn test_is_addable() {
        test_addable(Add, true);
        test_addable(ReadAdd, true);
        test_addable(ReadWrite, true);
        test_addable(AddWrite, true);
        test_addable(Eqv, false);
        test_addable(Read, false);
        test_addable(Write, false);
    }

    #[test]
    fn reads_partial_ordering() {
        let read = AccessRights::Read;
        assert_eq!(read == AccessRights::Read, true);
        assert_eq!(read < AccessRights::ReadAdd, true);
        assert_eq!(read < AccessRights::ReadWrite, true);
        assert_eq!(read != AccessRights::AddWrite, true);
        assert_eq!(read != AccessRights::Add, true);
        assert_eq!(read != AccessRights::Write, true);
    }

    #[test]
    fn adds_partial_ordering() {
        let add = AccessRights::Add;
        assert_eq!(add == AccessRights::Add, true);
        assert_eq!(add < AccessRights::ReadAdd, true);
        assert_eq!(add < AccessRights::ReadWrite, true);
        assert_eq!(add < AccessRights::AddWrite, true);
        assert_eq!(add != AccessRights::Read, true);
        assert_eq!(add != AccessRights::Write, true);
    }

    #[test]
    fn writes_partial_ordering() {
        let write = AccessRights::Write;
        assert_eq!(write == AccessRights::Write, true);
        assert_eq!(write < AccessRights::ReadWrite, true);
        assert_eq!(write < AccessRights::AddWrite, true);
        assert_eq!(write != AccessRights::Read, true);
        assert_eq!(write != AccessRights::Add, true);
        assert_eq!(write != AccessRights::ReadAdd, true);
    }

    proptest! {
        #[test]
        fn eqv_access_is_implicit(access_right in access_rights_arb()) {
            assert_eq!(AccessRights::Eqv <= access_right, true);
        }

        // According to the Rust documentation:
        // https://doc.rust-lang.org/std/hash/trait.Hash.html#hash-and-eq
        // following property must hold:
        // k1 == k2 -> hash(k1) == hash(k2)
        #[test]
        fn test_key_eqv_hash_property(key_a in key_arb(), key_b in key_arb()) {
            if key_a == key_b {
                let mut hasher = SipHasher::new();
                let key_a_hash = {
                    key_a.hash(&mut hasher);
                    hasher.finish()
                };
                let key_b_hash = {
                    key_b.hash(&mut hasher);
                    hasher.finish()
                };
                assert!(key_a_hash == key_b_hash);
            }
        }

        // According to Rust documentation, following property must hold::
        // forall a, b: a.cmp(b) == Ordering::Equal iff a == b and Some(a.cmp(b)) == a.partial_cmp(b)
        #[test]
        fn test_key_partialeq_partialord_ord_property(key_a in key_arb(), key_b in key_arb()) {
            if key_a == key_b && Some(key_a.cmp(&key_b)) == key_a.partial_cmp(&key_b) {
                assert!(key_a.cmp(&key_b) == Ordering::Equal);
            }
        }

        #[test]
        fn access_rights_addition_symmetric(rights_a in access_rights_arb(), rights_b in access_rights_arb()) {
            assert_eq!(rights_a + rights_b, rights_b + rights_a);
        }

        #[test]
        fn access_rights_addition_widen(rights_a in access_rights_arb(), rights_b in access_rights_arb()) {
            let merged_rights = rights_a + rights_b;
            match rights_a.partial_cmp(&rights_b) {
                Some(Ordering::Greater) => assert!(rights_a == merged_rights),
                Some(Ordering::Less) => assert!(rights_b == merged_rights),
                Some(Ordering::Equal) => assert!(rights_a == merged_rights && rights_b == merged_rights),
                None => {
                    assert_eq!(merged_rights + rights_a, merged_rights, "merged_rights = {:?}", merged_rights);
                    assert_eq!(merged_rights + rights_b, merged_rights, "merged_rights = {:?}", merged_rights);
                }
            }
        }
    }
}
